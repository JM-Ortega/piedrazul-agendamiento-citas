package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import java.util.Set;

public enum NotificationType {
    APPOINTMENT_SCHEDULED,
    APPOINTMENT_REMINDER_2_DAYS,
    OTP_CODE;

    // Futuros:
    // APPOINTMENT_CANCELLED,
    // APPOINTMENT_RESCHEDULED,
    // APPOINTMENT_CONFIRMED,
    // APPOINTMENT_NO_SHOW,
    // DOCTOR_DELAYED

    /**
     * Tipos cuyo contenido no debe aparecer en logs ordinarios (p. ej. un código de un solo uso).
     */
    public boolean containsSensitiveContent() {
        return this == OTP_CODE;
    }

    /** Variables secretas que no deben quedar guardadas una vez terminado el despacho. */
    public Set<String> sensitiveVariables() {
        return this == OTP_CODE ? Set.of(NotificationVariables.CODE) : Set.of();
    }

    /** Tipos cuyo contenido caduca y exige {@link NotificationVariables#EXPIRES_AT}. */
    public boolean requiresExpiry() {
        return this == OTP_CODE;
    }
}