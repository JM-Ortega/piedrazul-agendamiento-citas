package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.DeliveryEvent;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryEventRepository {

    DeliveryEvent save(DeliveryEvent event);

    Optional<DeliveryEvent> findById(UUID id);

    boolean existsByProviderNameAndProviderEventId(
            String providerName,
            String providerEventId
    );
}