package iua.edu.sentria.notificacion;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import iua.edu.sentria.SentriaApplication;
import iua.edu.sentria.model.InstruccionPreconsulta;
import iua.edu.sentria.model.TipoNotificacion;
import iua.edu.sentria.model.dto.DatosTurnoNotificacion;
import iua.edu.sentria.model.dto.MensajeNotificacion;

/**
 * Texto plano para que se lea igual en correo, webhook o un futuro canal de
 * mensajería. Las indicaciones se transcriben tal como las cargó la
 * institución: el sistema no agrega ni interpreta contenido clínico (RNF-07).
 */
@Component
public class PlantillaNotificacion {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final ZoneId ZONA = ZoneId.of(SentriaApplication.ZONA_INSTITUCIONAL);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", ES_AR);
    private static final DateTimeFormatter FECHA_CORTA = DateTimeFormatter.ofPattern("EEEE d/MM", ES_AR);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm", ES_AR);

    private final String portalUrl;
    private final int horasLimiteCambios;

    public PlantillaNotificacion(@Value("${sentria.portal.url}") String portalUrl,
            @Value("${sentria.turnos.horas-limite-cambios:24}") int horasLimiteCambios) {
        this.portalUrl = portalUrl.endsWith("/") ? portalUrl.substring(0, portalUrl.length() - 1) : portalUrl;
        this.horasLimiteCambios = horasLimiteCambios;
    }

    /**
     * @param instrucciones las de la especialidad o, si no tiene, las generales
     * @param especificas   true si instrucciones son de la especialidad
     */
    public MensajeNotificacion render(TipoNotificacion tipo, DatosTurnoNotificacion d,
            List<InstruccionPreconsulta> instrucciones, boolean especificas, OffsetDateTime ahora) {
        ZonedDateTime inicio = d.inicio().atZoneSameInstant(ZONA);
        String cuando = FECHA_CORTA.format(inicio) + " " + HORA.format(inicio);

        String asunto = tipo == TipoNotificacion.confirmacion
                ? "Turno confirmado: " + d.especialidad() + ", " + cuando
                : "Recordatorio de turno: " + d.especialidad() + ", " + cuando;

        StringBuilder sb = new StringBuilder();
        sb.append("Hola ").append(d.pacienteNombre()).append(",\n\n");
        sb.append(tipo == TipoNotificacion.confirmacion
                ? "Tu turno quedó confirmado. Estos son los datos:\n\n"
                : "Te recordamos que tenés un turno reservado:\n\n");

        sb.append("  Profesional:   ").append(d.profesional()).append('\n');
        sb.append("  Especialidad:  ").append(d.especialidad()).append('\n');
        sb.append("  Fecha:         ").append(FECHA.format(inicio)).append('\n');
        sb.append("  Hora:          ").append(HORA.format(inicio)).append(" hs\n");
        sb.append("  Sede:          ").append(d.sede()).append(" - ").append(d.sedeDireccion()).append('\n');
        sb.append("  Consultorio:   ").append(d.consultorio());
        if (d.consultorioPiso() != null && !d.consultorioPiso().isBlank()) {
            sb.append(" (piso ").append(d.consultorioPiso()).append(')');
        }
        sb.append("\n\n");

        if (!instrucciones.isEmpty()) {
            sb.append(especificas
                    ? "PREPARACIÓN NECESARIA PARA TU TURNO\n"
                    : "RECOMENDACIONES PARA EL DÍA DEL TURNO\n");
            for (InstruccionPreconsulta i : instrucciones) {
                sb.append("  - ").append(i.getTitulo()).append(": ").append(i.getDetalle()).append('\n');
            }
            if (especificas) {
                sb.append("Si no cumplís con la preparación, es posible que el estudio deba reprogramarse.\n");
            }
            sb.append("Estas indicaciones las define la institución y no reemplazan las de tu médico. "
                    + "Ante cualquier duda, consultá con tu profesional.\n\n");
        }

        sb.append("¿NECESITÁS CANCELAR O REPROGRAMAR?\n");
        if (Duration.between(ahora, d.inicio()).toHours() >= horasLimiteCambios) {
            sb.append("Podés hacerlo desde el portal hasta ").append(horasLimiteCambios)
                    .append(" horas antes del turno:\n");
        } else {
            sb.append("Faltan menos de ").append(horasLimiteCambios)
                    .append(" horas para el turno, por lo que ya no puede modificarse desde el portal. "
                            + "Comunicate con la sede. Podés ver el turno en:\n");
        }
        sb.append("  ").append(portalUrl).append("/turnos/").append(d.turnoId()).append("\n\n");

        sb.append("Sentria AI - Mensaje automático, por favor no lo respondas.\n");
        return new MensajeNotificacion(asunto, sb.toString());
    }
}
