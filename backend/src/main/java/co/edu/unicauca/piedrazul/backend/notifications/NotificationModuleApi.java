package co.edu.unicauca.piedrazul.backend.notifications;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.api.CancellationReason;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.api.ScheduleNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;

import java.util.UUID;

public interface NotificationModuleApi {

    UUID sendNow(SendNotificationCommand command);

    UUID schedule(ScheduleNotificationCommand command);

    void cancelPendingForAggregate(
            AggregateReference aggregate,
            CancellationReason reason
    );

    boolean alreadySent(
            AggregateReference aggregate,
            NotificationType notificationType
    );
}