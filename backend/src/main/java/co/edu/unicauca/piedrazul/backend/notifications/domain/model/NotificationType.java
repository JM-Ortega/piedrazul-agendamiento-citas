package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import java.util.Set;

public enum NotificationType {
    /** Confirmación de cita: ya no se genera; se conserva para leer las filas históricas. */
    APPOINTMENT_SCHEDULED,
    APPOINTMENT_REMINDER_2_DAYS,
    OTP_CODE;

    // Futuros:
    // APPOINTMENT_CANCELLED,
    // APPOINTMENT_RESCHEDULED,
    // APPOINTMENT_CONFIRMED,
    // APPOINTMENT_NO_SHOW,
    // DOCTOR_DELAYED

    /** Tipo que ya no se entrega: lo que quede en cola se cierra sin invocar proveedores. */
    public boolean isRetired() {
        return this == APPOINTMENT_SCHEDULED;
    }

    /** Solo se entrega mientras la cita de su agregado exista y siga AGENDADA. */
    public boolean requiresScheduledAppointment() {
        return this == APPOINTMENT_REMINDER_2_DAYS;
    }

    /**
     * Tipos cuyo contenido no debe aparecer en logs ordinarios (p. ej. un código de un solo uso).
     */
    public boolean containsSensitiveContent() {
        return this == OTP_CODE;
    }

    /**
     * Tipos cuyo contenido identifica a una persona y su atención médica. No son secretos, pero en
     * consola solo pueden mostrarse con el diagnóstico sintético explícito.
     */
    public boolean containsIdentifiableContent() {
        return this == APPOINTMENT_SCHEDULED || this == APPOINTMENT_REMINDER_2_DAYS;
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