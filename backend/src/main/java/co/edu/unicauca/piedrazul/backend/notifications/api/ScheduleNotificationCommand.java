package co.edu.unicauca.piedrazul.backend.notifications.api;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;

import java.time.Instant;
import java.util.Map;

public record ScheduleNotificationCommand(
        NotificationType type,
        AggregateReference aggregate,
        RecipientSnapshot recipient,
        Instant scheduledAt,
        Map<String, String> variables
) {
}