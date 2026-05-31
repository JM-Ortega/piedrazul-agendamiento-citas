package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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

    List<NotificationEntity> findAllByAggregateTypeAndAggregateIdAndStatusIn(
            AggregateType aggregateType,
            UUID aggregateId,
            List<NotificationStatus> statuses
    );
}