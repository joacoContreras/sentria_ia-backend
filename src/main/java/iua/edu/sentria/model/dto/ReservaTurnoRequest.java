package iua.edu.sentria.model.dto;

import java.time.OffsetDateTime;

import iua.edu.sentria.model.CanalOrigen;

// fin, sede y plan no se reciben: los deriva la BD de la agenda, el consultorio y la afiliación.
public record ReservaTurnoRequest(
        Long pacienteId,
        Long profesionalId,
        Long especialidadId,
        Long consultorioId,
        OffsetDateTime inicio,
        Long pacienteCoberturaId,
        CanalOrigen canal,
        boolean esSobreturno,
        String motivoConsulta,
        Long creadoPor) {
}
