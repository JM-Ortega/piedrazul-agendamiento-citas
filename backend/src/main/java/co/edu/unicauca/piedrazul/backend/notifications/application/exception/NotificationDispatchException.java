package co.edu.unicauca.piedrazul.backend.notifications.application.exception;

public class NotificationDispatchException extends RuntimeException {

    private final String errorCode;

    public NotificationDispatchException(
            String errorCode,
            String message
    ) {
        super(message);
        this.errorCode = errorCode;
    }

    public NotificationDispatchException(
            String errorCode,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}