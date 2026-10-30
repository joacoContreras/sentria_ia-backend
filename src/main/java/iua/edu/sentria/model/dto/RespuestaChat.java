package iua.edu.sentria.model.dto;

/**
 * alertaEmergencia: el front muestra el indicador visual de Alerta de
 * Emergencia (RF-07). reservaHabilitada = false corta el flujo de reserva.
 */
public record RespuestaChat(
        Tipo tipo,
        String respuesta,
        boolean alertaEmergencia,
        boolean reservaHabilitada,
        String especialidadSugerida) {

    public enum Tipo {
        administrativo, triage, emergencia, rechazo_clinico, fuera_de_dominio, intento_manipulacion
    }
}
