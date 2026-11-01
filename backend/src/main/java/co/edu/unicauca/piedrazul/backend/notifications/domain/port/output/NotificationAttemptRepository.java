package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;

import java.util.Optional;
import java.util.UUID;

public interface NotificationAttemptRepository {

    NotificationAttempt save(NotificationAttempt attempt);

    Optional<NotificationAttempt> findById(UUID id);

    Optional<NotificationAttempt> findByProviderMessageId(String providerMessageId);

    int countByNotificationIdAndChannel(
            UUID notificationId,
            co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel channel
    );

    int countByNotificationId(UUID notificationId);
}