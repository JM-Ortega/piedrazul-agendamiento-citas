package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import co.edu.unicauca.piedrazul.backend.notifications.domain.exception.InvalidAttemptStateTransitionException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class NotificationAttempt {

    private final UUID id;
    private final UUID notificationId;
    private final UUID scheduleId;
    private final NotificationChannel channel;
    private final String providerName;
    private final int attemptNumber;
    private final Instant createdAt;

    private String providerMessageId;
    private AttemptStatus status;
    private FailureType failureType;
    private String errorCode;
    private String errorMessage;
    private Instant sentAt;
    private Instant acceptedAt;
    private Instant deliveredAt;
    private Instant failedAt;
    private Instant updatedAt;

    private NotificationAttempt(
            UUID id,
            UUID notificationId,
            UUID scheduleId,
            NotificationChannel channel,
            String providerName,
            String providerMessageId,
            AttemptStatus status,
            int attemptNumber,
            FailureType failureType,
            String errorCode,
            String errorMessage,
            Instant sentAt,
            Instant acceptedAt,
            Instant deliveredAt,
            Instant failedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id, "El id del attempt es obligatorio");
        this.notificationId = Objects.requireNonNull(notificationId, "El id de la notificación es obligatorio");
        this.scheduleId = Objects.requireNonNull(scheduleId, "El id del schedule es obligatorio");
        this.channel = Objects.requireNonNull(channel, "El canal es obligatorio");
        this.providerName = Objects.requireNonNull(providerName, "El nombre del provider es obligatorio");
        this.providerMessageId = providerMessageId;
        this.status = Objects.requireNonNull(status, "El estado del attempt es obligatorio");
        this.attemptNumber = attemptNumber;
        this.failureType = failureType;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.sentAt = sentAt;
        this.acceptedAt = acceptedAt;
        this.deliveredAt = deliveredAt;
        this.failedAt = failedAt;
        this.createdAt = Objects.requireNonNull(createdAt, "La fecha de creación es obligatoria");
        this.updatedAt = Objects.requireNonNull(updatedAt, "La fecha de actualización es obligatoria");
    }

    public static NotificationAttempt create(
            UUID notificationId,
            UUID scheduleId,
            NotificationChannel channel,
            String providerName,
            int attemptNumber,
            Instant now
    ) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        return new NotificationAttempt(
                UUID.randomUUID(),
                notificationId,
                scheduleId,
                channel,
                providerName,
                null,
                AttemptStatus.PENDING,
                attemptNumber,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
    }

    public static NotificationAttempt reconstruct(
            UUID id,
            UUID notificationId,
            UUID scheduleId,
            NotificationChannel channel,
            String providerName,
            String providerMessageId,
            AttemptStatus status,
            int attemptNumber,
            FailureType failureType,
            String errorCode,
            String errorMessage,
            Instant sentAt,
            Instant acceptedAt,
            Instant deliveredAt,
            Instant failedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new NotificationAttempt(
                id,
                notificationId,
                scheduleId,
                channel,
                providerName,
                providerMessageId,
                status,
                attemptNumber,
                failureType,
                errorCode,
                errorMessage,
                sentAt,
                acceptedAt,
                deliveredAt,
                failedAt,
                createdAt,
                updatedAt
        );
    }

    public void markProcessing(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(AttemptStatus.PENDING);

        this.status = AttemptStatus.PROCESSING;
        this.updatedAt = now;
    }

    public void markSent(String providerMessageId, Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(AttemptStatus.PROCESSING);

        this.status = AttemptStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = now;
        this.updatedAt = now;
    }

    public void markAccepted(String providerMessageId, Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(AttemptStatus.PROCESSING, AttemptStatus.SENT);

        this.status = AttemptStatus.ACCEPTED;
        this.providerMessageId = providerMessageId;
        this.acceptedAt = now;
        this.updatedAt = now;
    }

    public void markDelivered(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(
                AttemptStatus.ACCEPTED,
                AttemptStatus.SENT,
                AttemptStatus.UNKNOWN
        );

        this.status = AttemptStatus.DELIVERED;
        this.deliveredAt = now;
        this.updatedAt = now;
    }

    public void markRead(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(
                AttemptStatus.DELIVERED,
                AttemptStatus.ACCEPTED,
                AttemptStatus.SENT,
                AttemptStatus.UNKNOWN
        );

        this.status = AttemptStatus.READ;
        this.deliveredAt = this.deliveredAt == null ? now : this.deliveredAt;
        this.updatedAt = now;
    }

    public void markFailed(
            FailureType failureType,
            String errorCode,
            String errorMessage,
            Instant now
    ) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        Objects.requireNonNull(failureType, "El tipo de fallo es obligatorio");
        requireStatus(
                AttemptStatus.PROCESSING,
                AttemptStatus.SENT,
                AttemptStatus.ACCEPTED,
                AttemptStatus.UNKNOWN
        );

        this.status = AttemptStatus.FAILED;
        this.failureType = failureType;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.failedAt = now;
        this.updatedAt = now;
    }

    public void markBounced(String errorCode, String errorMessage, Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatorio");
        requireStatus(AttemptStatus.SENT, AttemptStatus.ACCEPTED, AttemptStatus.UNKNOWN);

        this.status = AttemptStatus.BOUNCED;
        this.failureType = FailureType.PERMANENT;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.failedAt = now;
        this.updatedAt = now;
    }

    public void markUndelivered(String errorCode, String errorMessage, Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(AttemptStatus.SENT, AttemptStatus.ACCEPTED, AttemptStatus.UNKNOWN);

        this.status = AttemptStatus.UNDELIVERED;
        this.failureType = FailureType.PERMANENT;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.failedAt = now;
        this.updatedAt = now;
    }

    public void markUnknown(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(AttemptStatus.SENT, AttemptStatus.ACCEPTED);

        this.status = AttemptStatus.UNKNOWN;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(AttemptStatus.PENDING, AttemptStatus.PROCESSING);

        this.status = AttemptStatus.CANCELLED;
        this.updatedAt = now;
    }

    private void requireStatus(AttemptStatus... allowedStatuses) {
        for (AttemptStatus allowedStatus : allowedStatuses) {
            if (this.status == allowedStatus) {
                return;
            }
        }

        throw new InvalidAttemptStateTransitionException(
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

    public UUID getScheduleId() {
        return scheduleId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}