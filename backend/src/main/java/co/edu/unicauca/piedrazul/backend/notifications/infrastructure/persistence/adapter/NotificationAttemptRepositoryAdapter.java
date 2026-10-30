package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationAttemptRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationAttemptMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository.JpaNotificationAttemptRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationAttemptRepositoryAdapter
        implements NotificationAttemptRepository {

    private final JpaNotificationAttemptRepository repository;
    private final NotificationAttemptMapper mapper;

    public NotificationAttemptRepositoryAdapter(
            JpaNotificationAttemptRepository repository,
            NotificationAttemptMapper mapper
    ) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public NotificationAttempt save(NotificationAttempt attempt) {
        return mapper.toDomain(
                repository.save(
                        mapper.toEntity(attempt)
                )
        );
    }

    @Override
    public Optional<NotificationAttempt> findById(UUID id) {
        return repository.findById(id)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<NotificationAttempt> findByProviderMessageId(
            String providerMessageId
    ) {
        return repository.findFirstByProviderMessageId(providerMessageId)
                .map(mapper::toDomain);
    }

    @Override
    public int countByNotificationIdAndChannel(
            UUID notificationId,
            NotificationChannel channel
    ) {
        return repository.countByNotificationIdAndChannel(
                notificationId,
                channel
        );
    }

    @Override
    public int countByNotificationId(UUID notificationId) {
        return repository.countByNotificationId(notificationId);
    }
}