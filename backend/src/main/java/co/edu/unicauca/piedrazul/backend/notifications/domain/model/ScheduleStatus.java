package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

public enum ScheduleStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this == SENT
                || this == FAILED
                || this == CANCELLED
                || this == EXPIRED;
    }
}