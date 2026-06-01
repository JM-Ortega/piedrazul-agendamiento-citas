package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.exception;

public class NotificationPersistenceException extends RuntimeException {

    public NotificationPersistenceException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}