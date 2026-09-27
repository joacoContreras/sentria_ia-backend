package iua.edu.sentria.model.dto;

import iua.edu.sentria.model.CanalOrigen;
import iua.edu.sentria.model.EstadoTurno;

public record CambioEstadoTurnoRequest(
        EstadoTurno estado,
        Long usuarioId,
        CanalOrigen canal,
        String motivo) {
}
