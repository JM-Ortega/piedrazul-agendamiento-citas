package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;

/**
 * Resultado de un envío SMTP.
 *
 * @param health lo que el resultado dice de la salud del proveedor
 * @param code   código controlado, sin contenido del servidor ni del mensaje
 */
record SmtpOutcome(DeliveryOutcome outcome, Health health, String code, String providerMessageId) {

    /** Efecto sobre el circuit breaker. */
    enum Health {
        /** El proveedor funciona: cuenta como éxito. */
        HEALTHY,
        /** Falla el proveedor, el transporte o su configuración: cuenta como fallo. */
        FAULT,
        /** Decisión sobre el destinatario o el contenido: no dice nada del proveedor. */
        NEUTRAL
    }

    static SmtpOutcome accepted(String providerMessageId) {
        return new SmtpOutcome(DeliveryOutcome.ACCEPTED, Health.HEALTHY, null, providerMessageId);
    }

    static SmtpOutcome of(DeliveryOutcome outcome, Health health, String code) {
        return new SmtpOutcome(outcome, health, code, null);
    }
}
