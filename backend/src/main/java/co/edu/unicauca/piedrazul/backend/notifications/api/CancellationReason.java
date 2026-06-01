package co.edu.unicauca.piedrazul.backend.notifications.api;

public enum CancellationReason {

    // Razones externas, normalmente solicitadas por otros módulos
    APPOINTMENT_CANCELLED,
    APPOINTMENT_RESCHEDULED,
    REPLACED,
    MANUAL,

    // Razones internas usadas por notifications
    EXPIRED,
    DUPLICATED
}