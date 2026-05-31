package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ScheduleStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationScheduleMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository.JpaNotificationScheduleRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationScheduleRepositoryAdapter implements NotificationScheduleRepository {

    private static final List<ScheduleStatus> PENDING_STATUSES = List.of(
            ScheduleStatus.PENDING,
            ScheduleStatus.PROCESSING
    );

    private final JpaNotificationScheduleRepository repository;
    private final NotificationScheduleMapper mapper;

    public NotificationScheduleRepositoryAdapter(
            JpaNotificationScheduleRepository repository,
            NotificationScheduleMapper mapper
    ) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public NotificationSchedule save(NotificationSchedule schedule) {
        return mapper.toDomain(
                repository.save(
                        mapper.toEntity(schedule)
                )
        );
    }

    @Override
    public Optional<NotificationSchedule> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<NotificationSchedule> findDuePending(
            Instant now,
            int limit
    ) {
        return repository.findDuePendingWithLock(now, limit)
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<NotificationSchedule> findPendingByNotificationId(UUID notificationId) {
        return repository.findAllByNotificationIdAndStatusIn(
                        notificationId,
                        PENDING_STATUSES
                )
                .stream()
                .map(mapper::toDomain)
                .toList();
    }
}