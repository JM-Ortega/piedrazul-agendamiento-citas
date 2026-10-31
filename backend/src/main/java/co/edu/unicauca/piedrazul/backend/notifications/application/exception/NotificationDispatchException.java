package co.edu.unicauca.piedrazul.backend.notifications.application.exception;

import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;

public class NotificationDispatchException extends RuntimeException {

    /** Código con el que un proveedor indica que el circuit breaker rechazó la llamada sin invocarlo. */
    public static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    private final String errorCode;
    private final DeliveryOutcome outcome;

    public NotificationDispatchException(
            String errorCode,
            String message
    ) {
        this(errorCode, message, null, null);
    }

    public NotificationDispatchException(
            String errorCode,
            String message,
            Throwable cause
    ) {
        this(errorCode, message, null, cause);
    }

    /**
     * @param outcome evidencia del proveedor sobre la aceptación; {@code null} si no la hay, en
     *                cuyo caso el despachador no descarta que el mensaje se aceptara
     */
    public NotificationDispatchException(
            String errorCode,
            String message,
            DeliveryOutcome outcome,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.outcome = outcome;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public DeliveryOutcome getOutcome() {
        return outcome;
    }
}
