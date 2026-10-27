package iua.edu.sentria.model.dto;

import java.time.OffsetDateTime;

// Lo que necesita la plantilla de un turno. No incluye motivo_consulta: el
// mensaje viaja por canales no clínicos.
public record DatosTurnoNotificacion(
        Long turnoId,
        String estado,
        OffsetDateTime inicio,
        Long especialidadId,
        String especialidad,
        String pacienteNombre,
        String pacienteEmail,
        String pacienteTelefono,
        String profesional,
        String sede,
        String sedeDireccion,
        String consultorio,
        String consultorioPiso) {
}
