package iua.edu.sentria.model.dto;

import java.util.List;

// Historial de la conversación, el último mensaje es el del paciente.
public record ChatRequest(List<MensajeChat> mensajes) {
}
