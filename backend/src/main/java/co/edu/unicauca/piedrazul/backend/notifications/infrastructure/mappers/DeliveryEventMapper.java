package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.DeliveryEvent;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.DeliveryEventEntity;
import org.springframework.stereotype.Component;

@Component
public class DeliveryEventMapper {

    public DeliveryEventEntity toEntity(DeliveryEvent event) {
        if (event == null) {
            return null;
        }

        DeliveryEventEntity entity = new DeliveryEventEntity();

        entity.setId(event.getId());
        entity.setAttemptId(event.getAttemptId());

        entity.setProviderName(event.getProviderName());
        entity.setProviderMessageId(event.getProviderMessageId());
        entity.setProviderEventId(event.getProviderEventId());

        entity.setEventType(event.getEventType());
        entity.setRawStatus(event.getRawStatus());
        entity.setNormalizedStatus(event.getNormalizedStatus());

        entity.setPayloadJson(event.getPayloadJson());

        entity.setEventTimestamp(event.getEventTimestamp());
        entity.setReceivedAt(event.getReceivedAt());
        entity.setProcessedAt(event.getProcessedAt());

        return entity;
    }

    public DeliveryEvent toDomain(DeliveryEventEntity entity) {
        if (entity == null) {
            return null;
        }

        return DeliveryEvent.reconstruct(
                entity.getId(),
                entity.getAttemptId(),
                entity.getProviderName(),
                entity.getProviderMessageId(),
                entity.getProviderEventId(),
                entity.getEventType(),
                entity.getRawStatus(),
                entity.getNormalizedStatus(),
                entity.getPayloadJson(),
                entity.getEventTimestamp(),
                entity.getReceivedAt(),
                entity.getProcessedAt()
        );
    }
}