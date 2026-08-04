package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationEntity;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class NotificationMapper {

    public NotificationEntity toEntity(Notification notification) {
        if (notification == null) {
            return null;
        }

        NotificationEntity entity = new NotificationEntity();

        entity.setId(notification.getId());
        entity.setType(notification.getType());
        entity.setAggregateType(notification.getAggregate().type());
        entity.setAggregateId(notification.getAggregate().id());

        entity.setRecipientId(notification.getRecipient().recipientId());
        entity.setRecipientName(notification.getRecipient().displayName());
        entity.setRecipientPhoneE164(notification.getRecipient().phoneE164());
        entity.setRecipientEmail(notification.getRecipient().email());
        entity.setRecipientLocale(notification.getRecipient().locale().toLanguageTag());

        entity.setChannelPreference(notification.getChannelPreference());
        entity.setVariables(notification.getVariables());
        entity.setStatus(notification.getStatus());
        entity.setIdempotencyKey(notification.getIdempotencyKey());
        entity.setCreatedAt(notification.getCreatedAt());
        entity.setUpdatedAt(notification.getUpdatedAt());

        return entity;
    }

    public Notification toDomain(NotificationEntity entity) {
        if (entity == null) {
            return null;
        }

        AggregateReference aggregate = new AggregateReference(
                entity.getAggregateType(),
                entity.getAggregateId()
        );

        RecipientSnapshot recipient = new RecipientSnapshot(
                entity.getRecipientId(),
                entity.getRecipientName(),
                entity.getRecipientPhoneE164(),
                entity.getRecipientEmail(),
                Locale.forLanguageTag(entity.getRecipientLocale()),
                entity.getChannelPreference()
        );

        return Notification.reconstruct(
                entity.getId(),
                entity.getType(),
                aggregate,
                recipient,
                entity.getChannelPreference(),
                entity.getVariables(),
                entity.getIdempotencyKey(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}