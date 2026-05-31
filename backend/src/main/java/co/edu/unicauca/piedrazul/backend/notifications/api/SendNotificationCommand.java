package co.edu.unicauca.piedrazul.backend.notifications.api;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;

import java.util.Map;

public record SendNotificationCommand(
        NotificationType type,
        AggregateReference aggregate,
        RecipientSnapshot recipient,
        Map<String, String> variables
) {
}