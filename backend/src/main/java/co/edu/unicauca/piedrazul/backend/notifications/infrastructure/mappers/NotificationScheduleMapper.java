package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationScheduleEntity;
import org.springframework.stereotype.Component;

@Component
public class NotificationScheduleMapper {

    public NotificationScheduleEntity toEntity(NotificationSchedule schedule) {
        if (schedule == null) {
            return null;
        }

        NotificationScheduleEntity entity = new NotificationScheduleEntity();

        entity.setId(schedule.getId());
        entity.setNotificationId(schedule.getNotificationId());
        entity.setScheduledAt(schedule.getScheduledAt());
        entity.setStatus(schedule.getStatus());
        entity.setNextRetryAt(schedule.getNextRetryAt());
        entity.setProcessingStartedAt(schedule.getProcessingStartedAt());
        entity.setCancelledAt(schedule.getCancelledAt());
        entity.setCreatedAt(schedule.getCreatedAt());
        entity.setUpdatedAt(schedule.getUpdatedAt());

        return entity;
    }

    public NotificationSchedule toDomain(NotificationScheduleEntity entity) {
        if (entity == null) {
            return null;
        }

        return NotificationSchedule.reconstruct(
                entity.getId(),
                entity.getNotificationId(),
                entity.getScheduledAt(),
                entity.getStatus(),
                entity.getNextRetryAt(),
                entity.getProcessingStartedAt(),
                entity.getCancelledAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}