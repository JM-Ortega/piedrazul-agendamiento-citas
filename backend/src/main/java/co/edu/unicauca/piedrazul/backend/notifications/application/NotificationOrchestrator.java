package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.notifications.api.ScheduleNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationRepository;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogContext;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogger;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class NotificationOrchestrator {

    private final NotificationRepository notificationRepository;
    private final NotificationFactory notificationFactory;
    private final NotificationSchedulerService schedulerService;
    private final ChannelFallbackPolicy fallbackPolicy;

    public NotificationOrchestrator(
            NotificationRepository notificationRepository,
            NotificationFactory notificationFactory,
            NotificationSchedulerService schedulerService,
            ChannelFallbackPolicy fallbackPolicy
    ) {
        this.notificationRepository = notificationRepository;
        this.notificationFactory = notificationFactory;
        this.schedulerService = schedulerService;
        this.fallbackPolicy = fallbackPolicy;
    }

    public UUID sendNow(
            SendNotificationCommand command,
            Instant now
    ) {
        Objects.requireNonNull(command, "El comando de envío es obligatorio");
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        return notificationRepository
                .findActiveByAggregateAndType(command.aggregate(), command.type())
                .map(Notification::getId)
                .orElseGet(() -> createAndScheduleNow(command, now));
    }

    public UUID schedule(
            ScheduleNotificationCommand command,
            Instant now
    ) {
        Objects.requireNonNull(command, "El comando de programación es obligatorio");
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        return notificationRepository
                .findActiveByAggregateAndType(command.aggregate(), command.type())
                .map(Notification::getId)
                .orElseGet(() -> createAndScheduleAt(command, now));
    }

    public boolean alreadySent(
            AggregateReference aggregate,
            NotificationType notificationType
    ) {
        Objects.requireNonNull(aggregate, "El agregado es obligatorio");
        Objects.requireNonNull(notificationType, "El tipo de notificación es obligatorio");

        return notificationRepository
                .findActiveByAggregateAndType(aggregate, notificationType)
                .isPresent();
    }

    private UUID createAndScheduleNow(
            SendNotificationCommand command,
            Instant now
    ) {
        ChannelPreference channelPreference = resolveChannelPreference(
                command.recipient().channelPreference(),
                command.type()
        );

        Notification notification = notificationFactory.createNotification(
                command,
                channelPreference,
                now
        );

        Notification savedNotification = notificationRepository.save(notification);

        NotificationLogger.created(
                logContext(savedNotification)
        );

        schedulerService.scheduleNow(savedNotification, now);

        return savedNotification.getId();
    }

    private UUID createAndScheduleAt(
            ScheduleNotificationCommand command,
            Instant now
    ) {
        ChannelPreference channelPreference = resolveChannelPreference(
                command.recipient().channelPreference(),
                command.type()
        );

        Notification notification = notificationFactory.createNotification(
                command,
                channelPreference,
                now
        );

        Notification savedNotification = notificationRepository.save(notification);

        NotificationLogger.created(
                logContext(savedNotification)
        );

        schedulerService.scheduleAt(
                savedNotification,
                command.scheduledAt(),
                now
        );

        return savedNotification.getId();
    }

    private ChannelPreference resolveChannelPreference(
            ChannelPreference explicitPreference,
            NotificationType type
    ) {
        if (explicitPreference != null
                && explicitPreference.preferredOrder() != null
                && !explicitPreference.preferredOrder().isEmpty()) {
            return explicitPreference;
        }

        return new ChannelPreference(
                fallbackPolicy.resolveChannels(type)
        );
    }

    private NotificationLogContext logContext(Notification notification) {
        return NotificationLogContext
                .builder(notification.getId(), notification.getType())
                .aggregate(
                        notification.getAggregate().type(),
                        notification.getAggregate().id()
                )
                .build();
    }
}