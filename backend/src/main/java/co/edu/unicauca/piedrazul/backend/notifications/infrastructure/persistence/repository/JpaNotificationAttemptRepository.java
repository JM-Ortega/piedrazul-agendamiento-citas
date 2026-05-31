package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationAttemptEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaNotificationAttemptRepository
        extends JpaRepository<NotificationAttemptEntity, UUID> {

    Optional<NotificationAttemptEntity> findFirstByProviderMessageId(
            String providerMessageId
    );

    int countByNotificationIdAndChannel(
            UUID notificationId,
            NotificationChannel channel
    );

    List<NotificationAttemptEntity> findAllByStatusInAndUpdatedAtBefore(
            List<AttemptStatus> statuses,
            Instant threshold,
            Pageable pageable
    );
}