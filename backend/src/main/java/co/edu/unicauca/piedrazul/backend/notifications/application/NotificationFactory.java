package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.notifications.api.ScheduleNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ChannelPreference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;

import java.time.Instant;
import java.util.Objects;

public class NotificationFactory {

    public Notification createNotification(
            SendNotificationCommand command,
            ChannelPreference channelPreference,
            Instant now
    ) {
        Objects.requireNonNull(command, "El comando de envío es obligatorio");

        return Notification.create(
                command.type(),
                command.aggregate(),
                command.recipient(),
                channelPreference,
                command.variables(),
                now
        );
    }

    public Notification createNotification(
            ScheduleNotificationCommand command,
            ChannelPreference channelPreference,
            Instant now
    ) {
        Objects.requireNonNull(command, "El comando de programación es obligatorio");

        return Notification.create(
                command.type(),
                command.aggregate(),
                command.recipient(),
                channelPreference,
                command.variables(),
                now
        );
    }

    public NotificationSchedule createImmediateSchedule(
            Notification notification,
            Instant now
    ) {
        Objects.requireNonNull(notification, "La notificación es obligatoria");

        return NotificationSchedule.create(
                notification.getId(),
                now,
                now
        );
    }

    public NotificationSchedule createDelayedSchedule(
            Notification notification,
            Instant scheduledAt,
            Instant now
    ) {
        Objects.requireNonNull(notification, "La notificación es obligatoria");

        return NotificationSchedule.create(
                notification.getId(),
                scheduledAt,
                now
        );
    }
}