package co.edu.unicauca.piedrazul.backend.notifications.logging;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;

import java.util.UUID;

public final class NotificationLogContext {

    private final UUID notificationId;
    private final UUID scheduleId;
    private final UUID attemptId;
    private final AggregateType aggregateType;
    private final UUID aggregateId;
    private final NotificationType type;
    private final NotificationChannel channel;
    private final String providerName;
    private final Integer attemptNumber;

    private NotificationLogContext(
            UUID notificationId,
            UUID scheduleId,
            UUID attemptId,
            AggregateType aggregateType,
            UUID aggregateId,
            NotificationType type,
            NotificationChannel channel,
            String providerName,
            Integer attemptNumber
    ) {
        this.notificationId = notificationId;
        this.scheduleId = scheduleId;
        this.attemptId = attemptId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.type = type;
        this.channel = channel;
        this.providerName = providerName;
        this.attemptNumber = attemptNumber;
    }

    public static Builder builder(
            UUID notificationId,
            NotificationType type
    ) {
        return new Builder(notificationId, type);
    }

    public NotificationLogContext withSchedule(UUID scheduleId) {
        return new NotificationLogContext(
                notificationId,
                scheduleId,
                attemptId,
                aggregateType,
                aggregateId,
                type,
                channel,
                providerName,
                attemptNumber
        );
    }

    public NotificationLogContext withAttempt(
            UUID attemptId,
            int attemptNumber
    ) {
        return new NotificationLogContext(
                notificationId,
                scheduleId,
                attemptId,
                aggregateType,
                aggregateId,
                type,
                channel,
                providerName,
                attemptNumber
        );
    }

    public NotificationLogContext withChannel(
            NotificationChannel channel,
            String providerName
    ) {
        return new NotificationLogContext(
                notificationId,
                scheduleId,
                attemptId,
                aggregateType,
                aggregateId,
                type,
                channel,
                providerName,
                attemptNumber
        );
    }

    public UUID notificationId() {
        return notificationId;
    }

    public UUID scheduleId() {
        return scheduleId;
    }

    public UUID attemptId() {
        return attemptId;
    }

    public AggregateType aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public NotificationType type() {
        return type;
    }

    public NotificationChannel channel() {
        return channel;
    }

    public String providerName() {
        return providerName;
    }

    public Integer attemptNumber() {
        return attemptNumber;
    }

    public static final class Builder {

        private final UUID notificationId;
        private final NotificationType type;
        private AggregateType aggregateType;
        private UUID aggregateId;

        private Builder(
                UUID notificationId,
                NotificationType type
        ) {
            this.notificationId = notificationId;
            this.type = type;
        }

        public Builder aggregate(
                AggregateType aggregateType,
                UUID aggregateId
        ) {
            this.aggregateType = aggregateType;
            this.aggregateId = aggregateId;
            return this;
        }

        public NotificationLogContext build() {
            return new NotificationLogContext(
                    notificationId,
                    null,
                    null,
                    aggregateType,
                    aggregateId,
                    type,
                    null,
                    null,
                    null
            );
        }
    }
}