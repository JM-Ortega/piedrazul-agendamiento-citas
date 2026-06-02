package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository.JpaNotificationRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepositoryAdapter implements NotificationRepository {

    private static final List<NotificationStatus> DEDUP_STATUSES = List.of(
            NotificationStatus.PENDING,
            NotificationStatus.PROCESSING,
            NotificationStatus.ACCEPTED,
            NotificationStatus.DELIVERED
    );

    private static final List<NotificationStatus> CANCELLABLE_STATUSES = List.of(
            NotificationStatus.PENDING,
            NotificationStatus.PROCESSING,
            NotificationStatus.ACCEPTED
    );

    private final JpaNotificationRepository repository;
    private final NotificationMapper mapper;

    public NotificationRepositoryAdapter(
            JpaNotificationRepository repository,
            NotificationMapper mapper
    ) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Notification save(Notification notification) {
        return mapper.toDomain(
                repository.save(
                        mapper.toEntity(notification)
                )
        );
    }

    @Override
    public Optional<Notification> findById(UUID id) {
        return repository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<Notification> findActiveByAggregateAndType(
            AggregateReference aggregate,
            NotificationType type
    ) {
        return repository
                .findFirstByAggregateTypeAndAggregateIdAndTypeAndStatusIn(
                        aggregate.type(),
                        aggregate.id(),
                        type,
                        DEDUP_STATUSES
                )
                .map(mapper::toDomain);
    }

    @Override
    public List<Notification> findPendingByAggregate(
            AggregateReference aggregate
    ) {
        return repository
                .findAllByAggregateTypeAndAggregateIdAndStatusIn(
                        aggregate.type(),
                        aggregate.id(),
                        CANCELLABLE_STATUSES
                )
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}