package iua.edu.sentria.ia;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Filtro determinístico que corre ANTES y DESPUÉS del modelo. No depende de
 * OpenAI: si la API no responde, la alerta de emergencia sigue funcionando.
 *
 * Jerarquía de entrada: emergencia > inyección > solicitud clínica. La
 * emergencia gana aun dentro de un intento de manipulación ("ignorá tus
 * reglas, me duele el pecho" debe derivar a guardia igual).
 *
 * Los patrones viven en src/main/resources/ia/patrones/*.txt.
 */
@Component
public class AnalizadorSeguridad {

    private static final Pattern MARCAS_DIACRITICAS = Pattern.compile("\\p{M}+");
    private static final Pattern ESPACIOS = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private final List<Pattern> emergencia;
    private final List<Pattern> inyeccion;
    private final List<Pattern> solicitudClinica;
    private final List<Pattern> salidaClinica;

    public AnalizadorSeguridad() {
        emergencia = cargar("ia/patrones/emergencia.txt");
        inyeccion = cargar("ia/patrones/inyeccion.txt");
        solicitudClinica = cargar("ia/patrones/solicitud-clinica.txt");
        salidaClinica = cargar("ia/patrones/salida-clinica.txt");
    }

    public CategoriaRiesgo analizar(String mensaje) {
        String texto = normalizar(mensaje);
        if (coincide(emergencia, texto)) {
            return CategoriaRiesgo.EMERGENCIA;
        }
        if (coincide(inyeccion, texto)) {
            return CategoriaRiesgo.INYECCION;
        }
        if (coincide(solicitudClinica, texto)) {
            return CategoriaRiesgo.SOLICITUD_CLINICA;
        }
        return CategoriaRiesgo.NINGUNA;
    }

    public boolean esEmergencia(String mensaje) {
        return coincide(emergencia, normalizar(mensaje));
    }

    public boolean esInyeccion(String mensaje) {
        return coincide(inyeccion, normalizar(mensaje));
    }

    /** Patrón de salida violado por la respuesta del modelo, si hay alguno. */
    public Optional<String> violacionSalida(String respuesta) {
        String texto = normalizar(respuesta);
        return salidaClinica.stream().filter(p -> p.matcher(texto).find()).map(Pattern::pattern).findFirst();
    }

    /** Minúsculas, sin tildes ni diéresis (ñ queda n) y espacios colapsados; conserva los saltos de línea. */
    static String normalizar(String s) {
        if (s == null) {
            return "";
        }
        String sinTildes = MARCAS_DIACRITICAS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return ESPACIOS.matcher(sinTildes.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    private static boolean coincide(List<Pattern> patrones, String texto) {
        return patrones.stream().anyMatch(p -> p.matcher(texto).find());
    }

    private static List<Pattern> cargar(String recurso) {
        try (InputStream in = new ClassPathResource(recurso).getInputStream()) {
            List<Pattern> patrones = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> Pattern.compile(l, Pattern.MULTILINE))
                    .toList();
            if (patrones.isEmpty()) {
                throw new IllegalStateException("Política de seguridad vacía: " + recurso);
            }
            return patrones;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo cargar la política " + recurso, e);
        }
    }
}
