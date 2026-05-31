package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository;

import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.DeliveryEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaDeliveryEventRepository
        extends JpaRepository<DeliveryEventEntity, UUID> {

    Optional<DeliveryEventEntity>
    findFirstByProviderNameAndProviderEventId(
            String providerName,
            String providerEventId
    );

    Optional<DeliveryEventEntity>
    findFirstByProviderNameAndProviderMessageIdAndRawStatus(
            String providerName,
            String providerMessageId,
            String rawStatus
    );
}