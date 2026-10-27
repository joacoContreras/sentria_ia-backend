package iua.edu.sentria.business;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import iua.edu.sentria.business.exception.EnvioException;
import iua.edu.sentria.model.CanalNotificacion;
import iua.edu.sentria.model.EstadoNotificacion;
import iua.edu.sentria.model.EstadoTurno;
import iua.edu.sentria.model.InstruccionPreconsulta;
import iua.edu.sentria.model.Notificacion;
import iua.edu.sentria.model.NotificacionIncidente;
import iua.edu.sentria.model.dto.DatosTurnoNotificacion;
import iua.edu.sentria.model.dto.MensajeNotificacion;
import iua.edu.sentria.model.persistence.DatosNotificacionDAO;
import iua.edu.sentria.model.persistence.InstruccionPreconsultaRepository;
import iua.edu.sentria.model.persistence.NotificacionIncidenteRepository;
import iua.edu.sentria.model.persistence.NotificacionRepository;
import iua.edu.sentria.notificacion.INotificacionSender;
import iua.edu.sentria.notificacion.PlantillaNotificacion;
import lombok.extern.slf4j.Slf4j;

/**
 * Cada notificación se despacha en su propia transacción, con la fila
 * bloqueada (FOR UPDATE SKIP LOCKED) mientras se envía: así el envío
 * inmediato y el scheduler nunca mandan dos veces el mismo mensaje.
 * Ningún error de envío se propaga: queda en notificacion_incidente y en el
 * log, y la reserva (ya commiteada) no se entera (CA4).
 */
@Service
@Slf4j
public class NotificacionBusiness implements INotificacionBusiness {

    @Autowired
    private NotificacionRepository notificacionDAO;

    @Autowired
    private NotificacionIncidenteRepository incidenteDAO;

    @Autowired
    private InstruccionPreconsultaRepository instruccionDAO;

    @Autowired
    private DatosNotificacionDAO datosDAO;

    @Autowired
    private PlantillaNotificacion plantilla;

    private final Map<CanalNotificacion, INotificacionSender> senders = new EnumMap<>(CanalNotificacion.class);

    private final TransactionTemplate tx;

    @Value("${sentria.notificaciones.canal:email}")
    private CanalNotificacion canal;

    @Value("${sentria.notificaciones.recordatorio-horas-antes:24}")
    private int horasAntes;

    @Value("${sentria.notificaciones.max-intentos:3}")
    private int maxIntentos;

    @Value("${sentria.notificaciones.lote:50}")
    private int lote;

    public NotificacionBusiness(PlatformTransactionManager transactionManager, List<INotificacionSender> senders) {
        senders.forEach(s -> this.senders.put(s.canal(), s));
        tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void programar(long turnoId) {
        notificacionDAO.programar(turnoId, canal.name(), horasAntes);
    }

    @Async
    @Override
    public void despacharTurno(long turnoId) {
        try {
            notificacionDAO.vencidasDelTurno(turnoId).forEach(this::despachar);
        } catch (RuntimeException e) {
            // el scheduler la levanta en la próxima pasada
            log.error("No se pudieron despachar las notificaciones del turno id={}", turnoId, e);
        }
    }

    @Override
    public void despacharVencidas() {
        List<Long> ids;
        try {
            ids = notificacionDAO.vencidas(lote);
        } catch (RuntimeException e) {
            log.error("No se pudieron consultar las notificaciones pendientes", e);
            return;
        }
        ids.forEach(this::despachar);
    }

    private void despachar(long id) {
        try {
            tx.executeWithoutResult(status -> procesar(id));
        } catch (RuntimeException e) {
            log.error("Error procesando la notificación id={}", id, e);
        }
    }

    private void procesar(long id) {
        Optional<Notificacion> bloqueada = notificacionDAO.bloquearPendiente(id);
        if (bloqueada.isEmpty()) {
            return; // ya enviada/cancelada, o la está enviando otro hilo
        }
        Notificacion n = bloqueada.get();
        OffsetDateTime ahora = OffsetDateTime.now();
        DatosTurnoNotificacion datos = datosDAO.datosTurno(n.getTurnoId()).orElseThrow();

        // CA3: solo turnos que siguen reservados y no pasaron
        if (!EstadoTurno.reservado.name().equals(datos.estado()) || !datos.inicio().isAfter(ahora)) {
            n.setEstado(EstadoNotificacion.cancelada);
            log.debug("Notificación id={} cancelada: turno {} en estado {}", id, datos.turnoId(), datos.estado());
            return;
        }

        try {
            INotificacionSender sender = senders.get(n.getCanal());
            if (sender == null) {
                throw new EnvioException("Canal " + n.getCanal() + " sin emisor configurado", false);
            }
            sender.enviar(n.getPlantilla(), datos, mensaje(n, datos, ahora));
            n.setIntentos(n.getIntentos() + 1);
            n.setEstado(EstadoNotificacion.enviada);
            n.setEnviadaEn(ahora);
            log.info("Notificación id={} ({}) del turno {} enviada por {}",
                    id, n.getPlantilla(), n.getTurnoId(), n.getCanal());
        } catch (EnvioException e) {
            registrarFallo(n, e, ahora);
        } catch (RuntimeException e) {
            registrarFallo(n, new EnvioException("Error inesperado: " + e.getMessage(), true, e), ahora);
        }
    }

    private MensajeNotificacion mensaje(Notificacion n, DatosTurnoNotificacion d, OffsetDateTime ahora) {
        // CA2: las de la especialidad; si no tiene, las recomendaciones generales
        List<InstruccionPreconsulta> instrucciones =
                instruccionDAO.findByEspecialidadIdAndActivoTrueOrderByOrdenAsc(d.especialidadId());
        boolean especificas = !instrucciones.isEmpty();
        if (!especificas) {
            instrucciones = instruccionDAO.findByEspecialidadIdIsNullAndActivoTrueOrderByOrdenAsc();
        }
        return plantilla.render(n.getPlantilla(), d, instrucciones, especificas, ahora);
    }

    // Backoff lineal: 5, 10, 15... minutos. Agotados los intentos queda fallida.
    private void registrarFallo(Notificacion n, EnvioException e, OffsetDateTime ahora) {
        n.setIntentos(n.getIntentos() + 1);
        incidenteDAO.save(new NotificacionIncidente(n.getId(), n.getIntentos(), e.getMessage()));
        if (!e.isReintentable() || n.getIntentos() >= maxIntentos) {
            n.setEstado(EstadoNotificacion.fallida);
            log.error("Notificación id={} ({}) del turno {} FALLIDA tras {} intento(s): {}",
                    n.getId(), n.getPlantilla(), n.getTurnoId(), n.getIntentos(), e.getMessage());
        } else {
            n.setProgramadaPara(ahora.plusMinutes(5L * n.getIntentos()));
            log.warn("Notificación id={} ({}) del turno {}: intento {} fallido, se reintenta {}: {}",
                    n.getId(), n.getPlantilla(), n.getTurnoId(), n.getIntentos(), n.getProgramadaPara(),
                    e.getMessage());
        }
    }
}
