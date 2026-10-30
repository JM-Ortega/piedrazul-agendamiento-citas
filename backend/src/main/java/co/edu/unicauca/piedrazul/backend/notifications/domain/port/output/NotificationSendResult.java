package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;

/**
 * Resultado de {@link NotificationProvider#send}. {@link #outcome()} es lo que decide el
 * despachador; {@code status} y {@code failureType} se conservan por compatibilidad y para
 * persistir el intento.
 */
public record NotificationSendResult(
        String providerName,
        NotificationChannel channel,
        String providerMessageId,
        AttemptStatus status,
        FailureType failureType,
        String errorCode,
        String errorMessage,
        DeliveryOutcome outcome
) {

    public NotificationSendResult {
        if (outcome == null) {
            outcome = deriveOutcome(status, failureType);
        }
    }

    /** Constructor previo: el resultado se deduce del estado y del tipo de fallo. */
    public NotificationSendResult(
            String providerName,
            NotificationChannel channel,
            String providerMessageId,
            AttemptStatus status,
            FailureType failureType,
            String errorCode,
            String errorMessage
    ) {
        this(providerName, channel, providerMessageId, status, failureType, errorCode, errorMessage, null);
    }

    /**
     * Deducción conservadora para proveedores que no declaran {@link DeliveryOutcome}: solo se
     * infiere no aceptación cuando el resultado la afirma de forma explícita ({@code FAILED} con
     * {@code TEMPORARY}, {@code CIRCUIT_OPEN} o {@code PERMANENT}). Un estado de aceptación
     * (incluso DELIVERED o READ, que un envío síncrono no puede probar) cuenta como aceptado.
     * Todo lo demás es {@link DeliveryOutcome#UNKNOWN}, que no admite reintento ni cambio de canal:
     * un fallo sin clasificar, BOUNCED y UNDELIVERED (que presuponen una aceptación previa) y
     * CANCELLED. {@link DeliveryOutcome#PROVIDER_ERROR} nunca se deduce: solo lo declara un
     * proveedor de forma explícita.
     */
    static DeliveryOutcome deriveOutcome(AttemptStatus status, FailureType failureType) {
        if (status != AttemptStatus.FAILED) {
            return status == AttemptStatus.ACCEPTED
                    || status == AttemptStatus.SENT
                    || status == AttemptStatus.DELIVERED
                    || status == AttemptStatus.READ
                    ? DeliveryOutcome.ACCEPTED
                    : DeliveryOutcome.UNKNOWN;
        }

        if (failureType == null) {
            return DeliveryOutcome.UNKNOWN;
        }

        return switch (failureType) {
            case TEMPORARY, CIRCUIT_OPEN -> DeliveryOutcome.RETRYABLE;
            case PERMANENT -> DeliveryOutcome.REJECTED;
            case UNKNOWN -> DeliveryOutcome.UNKNOWN;
        };
    }
}
