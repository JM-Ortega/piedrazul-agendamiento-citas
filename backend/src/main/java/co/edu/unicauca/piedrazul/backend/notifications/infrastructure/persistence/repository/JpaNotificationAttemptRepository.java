package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationAttemptEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    int countByNotificationId(UUID notificationId);

    List<NotificationAttemptEntity> findAllByNotificationIdOrderByCreatedAtAscAttemptNumberAsc(UUID notificationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM NotificationAttemptEntity a WHERE a.id = :id")
    Optional<NotificationAttemptEntity> findByIdForUpdate(@Param("id") UUID id);
}
