package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaNotificationRepository
        extends JpaRepository<NotificationEntity, UUID> {

    Optional<NotificationEntity> findFirstByAggregateTypeAndAggregateIdAndTypeAndStatusIn(
            AggregateType aggregateType,
            UUID aggregateId,
            NotificationType type,
            List<NotificationStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT n FROM NotificationEntity n WHERE n.id = :id")
    Optional<NotificationEntity> findByIdForUpdate(@Param("id") UUID id);
}
