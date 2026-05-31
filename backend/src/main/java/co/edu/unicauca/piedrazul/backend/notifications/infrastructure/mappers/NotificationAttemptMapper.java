package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationAttemptEntity;
import org.springframework.stereotype.Component;

@Component
public class NotificationAttemptMapper {

    public NotificationAttemptEntity toEntity(NotificationAttempt attempt) {
        if (attempt == null) {
            return null;
        }

        NotificationAttemptEntity entity = new NotificationAttemptEntity();

        entity.setIdAttempt(attempt.getId());
        entity.setNotificationId(attempt.getNotificationId());
        entity.setScheduleId(attempt.getScheduleId());

        entity.setChannel(attempt.getChannel());
        entity.setProviderName(attempt.getProviderName());

        entity.setAttemptNumber(attempt.getAttemptNumber());

        entity.setStatus(attempt.getStatus());
        entity.setFailureType(attempt.getFailureType());

        entity.setProviderMessageId(attempt.getProviderMessageId());

        entity.setErrorCode(attempt.getErrorCode());
        entity.setErrorMessage(attempt.getErrorMessage());

        entity.setSentAt(attempt.getSentAt());
        entity.setAcceptedAt(attempt.getAcceptedAt());
        entity.setDeliveredAt(attempt.getDeliveredAt());
        entity.setFailedAt(attempt.getFailedAt());

        entity.setCreatedAt(attempt.getCreatedAt());
        entity.setUpdatedAt(attempt.getUpdatedAt());

        return entity;
    }

    public NotificationAttempt toDomain(NotificationAttemptEntity entity) {
        if (entity == null) {
            return null;
        }

        return NotificationAttempt.reconstruct(
                entity.getIdAttempt(),
                entity.getNotificationId(),
                entity.getScheduleId(),
                entity.getChannel(),
                entity.getProviderName(),
                entity.getProviderMessageId(),
                entity.getStatus(),
                entity.getAttemptNumber(),
                entity.getFailureType(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getSentAt(),
                entity.getAcceptedAt(),
                entity.getDeliveredAt(),
                entity.getFailedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}