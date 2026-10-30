package iua.edu.sentria.model.dto;

// No existe un rol "sistema": un JSON con otro rol falla al deserializar (400)
// y el system prompt solo lo pone el backend.
public record MensajeChat(Rol rol, String contenido) {

    public enum Rol {
        usuario, asistente
    }
}
