package iua.edu.sentria.business.exception;

import lombok.Builder;
import lombok.NoArgsConstructor;

// El recurso cambió o está tomado por otra operación (HTTP 409).
@NoArgsConstructor
public class ConflictException extends Exception {
    @Builder
    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(Throwable cause) {
        super(cause);
    }
}
