package iua.edu.sentria.business.exception;

import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public class NotFoundException extends Exception {
    @Builder
    public NotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotFoundException(String message) {
        super(message);
    }

    public NotFoundException(Throwable cause) {
        super(cause);
    }
}
