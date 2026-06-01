package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import co.edu.unicauca.piedrazul.backend.notifications.domain.exception.InvalidScheduleStateTransitionException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class NotificationSchedule {

    private final UUID id;
    private final UUID notificationId;
    private final Instant scheduledAt;
    private final Instant createdAt;
    private ScheduleStatus status;
    private Instant nextRetryAt;
    private Instant processingStartedAt;
    private Instant cancelledAt;
    private Instant updatedAt;

    private NotificationSchedule(
            UUID id,
            UUID notificationId,
            Instant scheduledAt,
            ScheduleStatus status,
            Instant nextRetryAt,
            Instant processingStartedAt,
            Instant cancelledAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "El id del schedule es obligatorio");
        this.notificationId = Objects.requireNonNull(notificationId, "El id de la notificación es obligatorio");
        this.scheduledAt = Objects.requireNonNull(scheduledAt, "La fecha programada es obligatoria");
        this.status = Objects.requireNonNull(status, "El estado del schedule es obligatorio");
        this.nextRetryAt = nextRetryAt;
        this.processingStartedAt = processingStartedAt;
        this.cancelledAt = cancelledAt;
        this.createdAt = Objects.requireNonNull(createdAt, "La fecha de creación es obligatoria");
        this.updatedAt = Objects.requireNonNull(updatedAt, "La fecha de actualización es obligatoria");
    }

    public static NotificationSchedule create(
            UUID notificationId,
            Instant scheduledAt,
            Instant now
    ) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        return new NotificationSchedule(
                UUID.randomUUID(),
                notificationId,
                scheduledAt,
                ScheduleStatus.PENDING,
                null,
                null,
                null,
                now,
                now
        );
    }

    public static NotificationSchedule reconstruct(
            UUID id,
            UUID notificationId,
            Instant scheduledAt,
            ScheduleStatus status,
            Instant nextRetryAt,
            Instant processingStartedAt,
            Instant cancelledAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new NotificationSchedule(
                id,
                notificationId,
                scheduledAt,
                status,
                nextRetryAt,
                processingStartedAt,
                cancelledAt,
                createdAt,
                updatedAt
        );
    }

    public void markProcessing(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(ScheduleStatus.PENDING, ScheduleStatus.PROCESSING);

        this.status = ScheduleStatus.PROCESSING;
        this.processingStartedAt = now;
        this.updatedAt = now;
    }

    public void markSent(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(ScheduleStatus.PROCESSING);

        this.status = ScheduleStatus.SENT;
        this.updatedAt = now;
    }

    public void scheduleRetry(Instant nextRetryAt, Instant now) {
        Objects.requireNonNull(nextRetryAt, "La fecha del siguiente retry es obligatoria");
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(ScheduleStatus.PROCESSING);

        this.status = ScheduleStatus.PENDING;
        this.nextRetryAt = nextRetryAt;
        this.updatedAt = now;
    }

    public void markFailed(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(ScheduleStatus.PENDING, ScheduleStatus.PROCESSING);

        this.status = ScheduleStatus.FAILED;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(ScheduleStatus.PENDING, ScheduleStatus.PROCESSING);

        this.status = ScheduleStatus.CANCELLED;
        this.cancelledAt = now;
        this.updatedAt = now;
    }

    public void markExpired(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(ScheduleStatus.PENDING, ScheduleStatus.PROCESSING);

        this.status = ScheduleStatus.EXPIRED;
        this.updatedAt = now;
    }

    private void requireStatus(ScheduleStatus... allowedStatuses) {
        for (ScheduleStatus allowedStatus : allowedStatuses) {
            if (this.status == allowedStatus) {
                return;
            }
        }

        throw new InvalidScheduleStateTransitionException(
                this.status,
                allowedStatuses
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationId() {
        return notificationId;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public ScheduleStatus getStatus() {
        return status;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}