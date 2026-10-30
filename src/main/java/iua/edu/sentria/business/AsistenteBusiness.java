package iua.edu.sentria.business;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import iua.edu.sentria.business.exception.BusinessException;
import iua.edu.sentria.business.exception.InvalidException;
import iua.edu.sentria.ia.AnalizadorSeguridad;
import iua.edu.sentria.ia.CategoriaRiesgo;
import iua.edu.sentria.ia.IClienteLLM;
import iua.edu.sentria.ia.IClienteLLM.MensajeLLM;
import iua.edu.sentria.ia.IClienteLLM.Moderacion;
import iua.edu.sentria.ia.PromptSistema;
import iua.edu.sentria.model.dto.ChatRequest;
import iua.edu.sentria.model.dto.MensajeChat;
import iua.edu.sentria.model.dto.RespuestaChat;
import iua.edu.sentria.model.dto.RespuestaChat.Tipo;
import iua.edu.sentria.model.persistence.EspecialidadDAO;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orquestación del asistente (RF-06, RF-07, RNF-07). Capas, en orden:
 *  1. filtro determinístico sobre la entrada (emergencia > inyección > pedido clínico);
 *  2. Moderation API de OpenAI (autolesión se trata como emergencia);
 *  3. modelo con el system prompt inmutable y salida JSON;
 *  4. guardrail de salida: canario del prompt, patrones clínicos y lista
 *     blanca de especialidades.
 * Las respuestas de emergencia, manipulación y rechazo clínico son textos
 * fijos: no pasan por el modelo, así no dependen de que obedezca.
 */
@Service
@Slf4j
public class AsistenteBusiness implements IAsistenteBusiness {

    static final int MAX_MENSAJES = 20;
    static final int MAX_CARACTERES = 2000;

    static final RespuestaChat EMERGENCIA = new RespuestaChat(Tipo.emergencia,
            "ALERTA DE EMERGENCIA. Lo que describís puede ser una situación de riesgo para tu vida. "
                    + "No esperes un turno programado: dirigite AHORA a la guardia de emergencias más cercana "
                    + "o llamá al 107 (emergencias médicas) o al 911. Si estás con alguien, pedile que te acompañe. "
                    + "Este asistente no puede evaluar síntomas ni reemplaza la atención de urgencia.",
            true, false, null);

    static final RespuestaChat MANIPULACION = new RespuestaChat(Tipo.intento_manipulacion,
            "No puedo cambiar mi función ni mis reglas. Soy el asistente administrativo del centro médico: "
                    + "puedo ayudarte a sacar, consultar, reprogramar o cancelar turnos, y a resolver consultas "
                    + "sobre el centro. ¿En qué te ayudo?",
            false, true, null);

    static final RespuestaChat RECHAZO_CLINICO = new RespuestaChat(Tipo.rechazo_clinico,
            "No puedo dar diagnósticos, indicar tratamientos ni medicamentos, ni emitir recetas u órdenes: "
                    + "eso solo puede hacerlo un profesional durante la consulta. Si querés, te ayudo a buscar "
                    + "un turno con la especialidad que corresponda.",
            false, true, null);

    static final RespuestaChat FUERA_DE_DOMINIO = new RespuestaChat(Tipo.fuera_de_dominio,
            "Disculpá, solo puedo ayudarte con temas del centro médico: turnos, especialidades, sedes, "
                    + "coberturas y trámites. ¿Querés que te ayude con algún turno?",
            false, true, null);

    static final RespuestaChat SIN_RESPUESTA = new RespuestaChat(Tipo.administrativo,
            "Disculpá, no pude procesar tu consulta. ¿Podés reformularla? También podés comunicarte con recepción.",
            false, true, null);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SalidaModelo(String tipo, String respuesta, String especialidad) {
    }

    private final AnalizadorSeguridad analizador;
    private final PromptSistema prompt;
    private final IClienteLLM llm;
    private final EspecialidadDAO especialidadDAO;
    private final boolean moderacionHabilitada;
    private final JsonMapper json = JsonMapper.builder().build();

    public AsistenteBusiness(AnalizadorSeguridad analizador, PromptSistema prompt, IClienteLLM llm,
            EspecialidadDAO especialidadDAO,
            @Value("${sentria.ia.moderacion.habilitada:true}") boolean moderacionHabilitada) {
        this.analizador = analizador;
        this.prompt = prompt;
        this.llm = llm;
        this.especialidadDAO = especialidadDAO;
        this.moderacionHabilitada = moderacionHabilitada;
    }

    @Override
    public RespuestaChat responder(ChatRequest chat) throws InvalidException, BusinessException {
        List<MensajeChat> mensajes = validar(chat);
        String ultimo = mensajes.get(mensajes.size() - 1).contenido();

        CategoriaRiesgo riesgo = analizador.analizar(ultimo);
        if (riesgo == CategoriaRiesgo.EMERGENCIA) {
            log.warn("Alerta de emergencia activada por el filtro de entrada");
            return EMERGENCIA;
        }
        // El historial lo manda el cliente: un turno "asistente" falsificado también es inyección.
        if (riesgo == CategoriaRiesgo.INYECCION
                || mensajes.stream().anyMatch(m -> analizador.esInyeccion(m.contenido()))) {
            log.warn("Intento de prompt injection bloqueado");
            return MANIPULACION;
        }
        if (riesgo == CategoriaRiesgo.SOLICITUD_CLINICA) {
            return RECHAZO_CLINICO;
        }

        if (moderacionHabilitada) {
            try {
                Moderacion m = llm.moderar(ultimo);
                if (m.autolesion()) {
                    log.warn("Alerta de emergencia activada por moderación: {}", m.categorias());
                    return EMERGENCIA;
                }
                if (m.marcado()) {
                    return FUERA_DE_DOMINIO;
                }
            } catch (BusinessException e) {
                // Los filtros locales ya corrieron; no se corta la atención por la moderación.
                log.warn("Moderación no disponible: {}", e.getMessage());
            }
        }

        List<String> especialidades = especialidadDAO.activas();
        List<MensajeLLM> entrada = new ArrayList<>(mensajes.size() + 1);
        entrada.add(new MensajeLLM("system", prompt.construir(especialidades)));
        for (MensajeChat m : mensajes) {
            entrada.add(new MensajeLLM(m.rol() == MensajeChat.Rol.usuario ? "user" : "assistant", m.contenido()));
        }
        return interpretar(llm.completar(entrada), especialidades);
    }

    RespuestaChat interpretar(String salida, List<String> especialidades) {
        if (PromptSistema.filtraPrompt(salida)) {
            log.warn("La respuesta del modelo contenía el canario del system prompt");
            return MANIPULACION;
        }
        SalidaModelo s;
        try {
            s = json.readValue(sinCercoMarkdown(salida), SalidaModelo.class);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Respuesta del modelo no es JSON válido: {}", e.getMessage());
            return SIN_RESPUESTA;
        }
        if (s == null || s.respuesta() == null || s.respuesta().isBlank()) {
            return SIN_RESPUESTA;
        }

        Tipo tipo = tipo(s.tipo());
        if (tipo == Tipo.emergencia) {
            log.warn("Alerta de emergencia activada por el modelo");
            return EMERGENCIA;
        }
        var violacion = analizador.violacionSalida(s.respuesta());
        if (violacion.isPresent()) {
            log.warn("Respuesta del modelo bloqueada por guardrail de salida: {}", violacion.get());
            return RECHAZO_CLINICO;
        }

        String especialidad = null;
        if (tipo == Tipo.triage) {
            especialidad = enLista(s.especialidad(), especialidades);
            if (especialidad == null) {
                tipo = Tipo.administrativo;
            }
        }
        return new RespuestaChat(tipo, s.respuesta().strip(), false, true, especialidad);
    }

    private static List<MensajeChat> validar(ChatRequest chat) throws InvalidException {
        if (chat == null || chat.mensajes() == null || chat.mensajes().isEmpty()) {
            throw new InvalidException("La conversación no tiene mensajes");
        }
        if (chat.mensajes().size() > MAX_MENSAJES) {
            throw new InvalidException("La conversación supera los " + MAX_MENSAJES + " mensajes");
        }
        for (MensajeChat m : chat.mensajes()) {
            if (m == null || m.rol() == null || m.contenido() == null || m.contenido().isBlank()) {
                throw new InvalidException("Cada mensaje requiere rol y contenido");
            }
            if (m.contenido().length() > MAX_CARACTERES) {
                throw new InvalidException("Un mensaje supera los " + MAX_CARACTERES + " caracteres");
            }
        }
        if (chat.mensajes().get(chat.mensajes().size() - 1).rol() != MensajeChat.Rol.usuario) {
            throw new InvalidException("El último mensaje debe ser del usuario");
        }
        return chat.mensajes();
    }

    private static Tipo tipo(String s) {
        if (s != null) {
            for (Tipo t : Tipo.values()) {
                if (t.name().equalsIgnoreCase(s.strip())) {
                    return t;
                }
            }
        }
        return Tipo.administrativo;
    }

    // El modelo solo puede sugerir una especialidad que exista y esté activa.
    private static String enLista(String sugerida, List<String> especialidades) {
        if (sugerida == null) {
            return null;
        }
        String clave = clave(sugerida);
        return especialidades.stream().filter(e -> clave(e).equals(clave)).findFirst().orElse(null);
    }

    private static String clave(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").strip()
                .toLowerCase(Locale.ROOT);
    }

    private static String sinCercoMarkdown(String s) {
        String t = s == null ? "" : s.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        return t;
    }
}
