package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

public enum AttemptStatus {
    PENDING,
    PROCESSING,
    ACCEPTED,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    BOUNCED,
    UNDELIVERED,
    CANCELLED,
    UNKNOWN;

    public boolean isTerminal() {
        return this == DELIVERED
                || this == READ
                || this == FAILED
                || this == BOUNCED
                || this == UNDELIVERED
                || this == CANCELLED;
    }
}