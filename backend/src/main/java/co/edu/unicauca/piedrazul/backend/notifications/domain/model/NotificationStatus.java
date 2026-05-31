package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

public enum NotificationStatus {
    PENDING,
    PROCESSING,
    ACCEPTED,
    DELIVERED,
    FAILED,
    CANCELLED,
    EXPIRED;

    public boolean isTerminal() {
        return this == DELIVERED
                || this == FAILED
                || this == CANCELLED
                || this == EXPIRED;
    }
}