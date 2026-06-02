package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationAttemptRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationAttemptMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository.JpaNotificationAttemptRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationAttemptRepositoryAdapter
        implements NotificationAttemptRepository {

    private static final List<AttemptStatus> STALE_STATUSES = List.of(
            AttemptStatus.SENT,
            AttemptStatus.ACCEPTED,
            AttemptStatus.UNKNOWN
    );

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
    public List<NotificationAttempt> findStaleAttempts(
            Instant threshold,
            int limit
    ) {
        return repository.findAllByStatusInAndUpdatedAtBefore(
                        STALE_STATUSES,
                        threshold,
                        PageRequest.of(0, limit)
                )
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}