package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {

    Notification save(Notification notification);

    Optional<Notification> findById(UUID id);

    /**
     * Notificación del agregado y tipo en cualquier estado, incluso terminal: la clave de
     * idempotencia es única sin importar el estado.
     */
    Optional<Notification> findByAggregateAndType(
            AggregateReference aggregate,
            NotificationType type
    );

    Optional<Notification> findActiveByAggregateAndType(
            AggregateReference aggregate,
            NotificationType type
    );
}