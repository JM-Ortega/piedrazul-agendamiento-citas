package co.edu.unicauca.piedrazul.backend.audit.domain;

public enum AuditAction {
    // usuarios
    USER_CREATED,
    USER_UPDATED,
    USER_DEACTIVATED,
    LOGIN_SUCCESS,
    LOGIN_FAILED,

    // citas
    APPOINTMENT_CREATED,
    APPOINTMENT_RESCHEDULED,

    // historia clínica
    CLINICAL_RECORD_CREATED
}
