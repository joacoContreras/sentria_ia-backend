package iua.edu.sentria.business.exception;

import lombok.Builder;
import lombok.NoArgsConstructor;

// La operación viola una regla de negocio de los datos enviados (HTTP 422).
@NoArgsConstructor
public class InvalidException extends Exception {
    @Builder
    public InvalidException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidException(String message) {
        super(message);
    }

    public InvalidException(Throwable cause) {
        super(cause);
    }
}
