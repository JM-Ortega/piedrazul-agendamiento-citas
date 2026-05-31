package co.edu.unicauca.piedrazul.backend.notifications.api;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;

public record CancelPendingNotificationsCommand(
        AggregateReference aggregate,
        CancellationReason reason
) {
}