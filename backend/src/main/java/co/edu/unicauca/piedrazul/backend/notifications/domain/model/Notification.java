package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import co.edu.unicauca.piedrazul.backend.notifications.domain.exception.InvalidNotificationStateTransitionException;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class Notification {

    private final UUID id;
    private final NotificationType type;
    private final AggregateReference aggregate;
    private final RecipientSnapshot recipient;
    private final ChannelPreference channelPreference;
    private final Map<String, String> variables;
    private final String idempotencyKey;
    private final Instant createdAt;

    private NotificationStatus status;
    private Instant updatedAt;

    private Notification(
            UUID id,
            NotificationType type,
            AggregateReference aggregate,
            RecipientSnapshot recipient,
            ChannelPreference channelPreference,
            Map<String, String> variables,
            String idempotencyKey,
            NotificationStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.type = Objects.requireNonNull(type, "El tipo de notificación es obligatorio");
        this.aggregate = Objects.requireNonNull(aggregate, "El agregado es obligatorio");
        this.recipient = Objects.requireNonNull(recipient, "El destinatario es obligatorio");
        this.channelPreference = Objects.requireNonNull(
                channelPreference,
                "La preferencia de canal es obligatoria"
        );

        Objects.requireNonNull(
                variables,
                "Las variables de template son obligatorias"
        );

        this.variables = Map.copyOf(variables);

        this.idempotencyKey = Objects.requireNonNull(
                idempotencyKey,
                "La clave de idempotencia es obligatoria"
        );

        this.status = Objects.requireNonNull(
                status,
                "El estado de la notificación es obligatorio"
        );

        this.createdAt = Objects.requireNonNull(
                createdAt,
                "La fecha de creación es obligatoria"
        );

        this.updatedAt = Objects.requireNonNull(
                updatedAt,
                "La fecha de actualización es obligatoria"
        );
    }

    public static Notification create(
            NotificationType type,
            AggregateReference aggregate,
            RecipientSnapshot recipient,
            ChannelPreference channelPreference,
            Map<String, String> variables,
            Instant now
    ) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        return new Notification(
                UUID.randomUUID(),
                type,
                aggregate,
                recipient,
                channelPreference,
                variables,
                buildIdempotencyKey(aggregate, type),
                NotificationStatus.PENDING,
                now,
                now
        );
    }

    public static Notification reconstruct(
            UUID id,
            NotificationType type,
            AggregateReference aggregate,
            RecipientSnapshot recipient,
            ChannelPreference channelPreference,
            Map<String, String> variables,
            String idempotencyKey,
            NotificationStatus status,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new Notification(
                id,
                type,
                aggregate,
                recipient,
                channelPreference,
                variables,
                idempotencyKey,
                status,
                createdAt,
                updatedAt
        );
    }

    private static String buildIdempotencyKey(
            AggregateReference aggregate,
            NotificationType type
    ) {
        return aggregate.type().name()
                + ":"
                + aggregate.id()
                + ":"
                + type.name();
    }

    public void markProcessing(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(NotificationStatus.PENDING, NotificationStatus.PROCESSING);

        this.status = NotificationStatus.PROCESSING;
        this.updatedAt = now;
    }

    public void markAccepted(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(NotificationStatus.PROCESSING);

        this.status = NotificationStatus.ACCEPTED;
        this.updatedAt = now;
    }

    public void markDelivered(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(NotificationStatus.ACCEPTED);

        this.status = NotificationStatus.DELIVERED;
        this.updatedAt = now;
    }

    public void markFailed(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(
                NotificationStatus.PROCESSING,
                NotificationStatus.ACCEPTED
        );

        this.status = NotificationStatus.FAILED;
        this.updatedAt = now;
    }

    public void markCancelled(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");
        requireStatus(
                NotificationStatus.PENDING,
                NotificationStatus.PROCESSING
        );

        this.status = NotificationStatus.CANCELLED;
        this.updatedAt = now;
    }

    public void markExpired(Instant now) {
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        requireStatus(
                NotificationStatus.PENDING,
                NotificationStatus.PROCESSING,
                NotificationStatus.ACCEPTED
        );

        this.status = NotificationStatus.EXPIRED;
        this.updatedAt = now;
    }

    private void requireStatus(NotificationStatus... allowedStatuses) {
        for (NotificationStatus allowedStatus : allowedStatuses) {
            if (this.status == allowedStatus) {
                return;
            }
        }

        throw new InvalidNotificationStateTransitionException(
                this.status,
                allowedStatuses
        );
    }

    public UUID getId() {
        return id;
    }

    public NotificationType getType() {
        return type;
    }

    public AggregateReference getAggregate() {
        return aggregate;
    }

    public RecipientSnapshot getRecipient() {
        return recipient;
    }

    public ChannelPreference getChannelPreference() {
        return channelPreference;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}