package iua.edu.sentria.business.exception;

import lombok.Getter;

// Falló el despacho de una notificación. reintentable = false cuando insistir
// no cambia el resultado (paciente sin email, canal sin configurar, 4xx).
@Getter
public class EnvioException extends Exception {
    private final boolean reintentable;

    public EnvioException(String message, boolean reintentable, Throwable cause) {
        super(message, cause);
        this.reintentable = reintentable;
    }

    public EnvioException(String message, boolean reintentable) {
        this(message, reintentable, null);
    }
}
