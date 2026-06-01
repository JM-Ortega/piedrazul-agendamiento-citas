package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;

import java.time.Instant;
import java.util.List;
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

    /**
     * Retorna attempts en estado ACCEPTED, SENT o UNKNOWN
     * cuyo acceptedAt o sentAt sea anterior al threshold dado.
     */
    List<NotificationAttempt> findStaleAttempts(
            Instant threshold,
            int limit
    );
}