package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.DeliveryEvent;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryEventRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.DeliveryEventMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository.JpaDeliveryEventRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class DeliveryEventRepositoryAdapter implements DeliveryEventRepository {

    private final JpaDeliveryEventRepository repository;
    private final DeliveryEventMapper mapper;

    public DeliveryEventRepositoryAdapter(
            JpaDeliveryEventRepository repository,
            DeliveryEventMapper mapper
    ) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public DeliveryEvent save(DeliveryEvent event) {
        return mapper.toDomain(
                repository.save(
                        mapper.toEntity(event)
                )
        );
    }

    @Override
    public Optional<DeliveryEvent> findById(UUID id) {
        return repository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public boolean existsByProviderNameAndProviderEventId(
            String providerName,
            String providerEventId
    ) {
        if (providerEventId == null || providerEventId.isBlank()) {
            return false;
        }

        return repository
                .findFirstByProviderNameAndProviderEventId(
                        providerName,
                        providerEventId
                )
                .isPresent();
    }
}