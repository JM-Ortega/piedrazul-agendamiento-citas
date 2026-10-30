package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ScheduleStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DispatchWrite;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationAttemptMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationScheduleMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationAttemptEntity;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationEntity;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationScheduleEntity;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository.JpaNotificationAttemptRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository.JpaNotificationRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository.JpaNotificationScheduleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementación JPA de {@link NotificationDispatchStore}. Orden de bloqueo: schedule, después
 * notificación, después intento. Los cambios se aplican sobre entidades recién leídas dentro de
 * la transacción, nunca sobre objetos que vengan de fuera de ella.
 */
@Repository
public class NotificationDispatchStoreAdapter implements NotificationDispatchStore {

    private final JpaNotificationScheduleRepository scheduleRepository;
    private final JpaNotificationRepository notificationRepository;
    private final JpaNotificationAttemptRepository attemptRepository;
    private final NotificationScheduleMapper scheduleMapper;
    private final NotificationMapper notificationMapper;
    private final NotificationAttemptMapper attemptMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public NotificationDispatchStoreAdapter(
            JpaNotificationScheduleRepository scheduleRepository,
            JpaNotificationRepository notificationRepository,
            JpaNotificationAttemptRepository attemptRepository,
            NotificationScheduleMapper scheduleMapper,
            NotificationMapper notificationMapper,
            NotificationAttemptMapper attemptMapper
    ) {
        this.scheduleRepository = scheduleRepository;
        this.notificationRepository = notificationRepository;
        this.attemptRepository = attemptRepository;
        this.scheduleMapper = scheduleMapper;
        this.notificationMapper = notificationMapper;
        this.attemptMapper = attemptMapper;
    }

    @Override
    @Transactional
    public Begin begin(UUID scheduleId, int claimedAttemptCount, Instant now) {
        Optional<NotificationSchedule> owned = lockOwnedSchedule(scheduleId, claimedAttemptCount);

        if (owned.isEmpty()) {
            return Begin.of(Begin.Status.OWNERSHIP_LOST);
        }

        NotificationSchedule schedule = owned.get();
        Optional<NotificationEntity> notificationEntity =
                notificationRepository.findByIdForUpdate(schedule.getNotificationId());

        if (notificationEntity.isEmpty()) {
            schedule.markFailed(now);
            saveSchedule(schedule);
            return Begin.of(Begin.Status.NOT_DISPATCHABLE);
        }

        Notification notification = notificationMapper.toDomain(notificationEntity.get());

        switch (notification.getStatus()) {
            case PENDING -> {
                notification.markProcessing(now);
                saveNotification(notification);
            }
            case PROCESSING -> {
            }
            default -> {
                closeScheduleOfSettledNotification(notification, schedule, now);
                return Begin.of(Begin.Status.NOT_DISPATCHABLE);
            }
        }

        List<NotificationAttempt> attempts = attemptRepository
                .findAllByNotificationIdOrderByCreatedAtAscAttemptNumberAsc(notification.getId())
                .stream()
                .map(attemptMapper::toDomain)
                .toList();

        return new Begin(Begin.Status.READY, notification, schedule, attempts);
    }

    @Override
    @Transactional
    public WriteResult write(DispatchWrite write) {
        Optional<NotificationSchedule> owned = lockOwnedSchedule(write.scheduleId(), write.expectedAttemptCount());

        if (owned.isEmpty()) {
            recordOwnAttemptOnly(write.resolvedAttempt());
            return WriteResult.OWNERSHIP_LOST;
        }

        NotificationSchedule schedule = owned.get();
        Optional<Notification> lockedNotification = notificationRepository
                .findByIdForUpdate(schedule.getNotificationId())
                .map(notificationMapper::toDomain)
                .filter(notification -> notification.getStatus() == NotificationStatus.PROCESSING);

        if (lockedNotification.isEmpty()) {
            recordOwnAttemptOnly(write.resolvedAttempt());
            return WriteResult.OWNERSHIP_LOST;
        }

        Notification notification = lockedNotification.get();

        if (write.newAttempt() != null) {
            // persist, no merge: un id repetido falla en lugar de sobrescribir
            entityManager.persist(attemptMapper.toEntity(write.newAttempt()));
        }

        if (write.resolvedAttempt() != null) {
            Optional<NotificationAttemptEntity> current = attemptRepository.findByIdForUpdate(write.resolvedAttempt().getId());

            if (current.isEmpty() || current.get().getStatus() != AttemptStatus.PROCESSING) {
                return WriteResult.OWNERSHIP_LOST;
            }

            attemptRepository.save(attemptMapper.toEntity(write.resolvedAttempt()));
        }

        write.finish().applyTo(notification, schedule, write.retryAt(), write.now());
        saveNotification(notification);
        saveSchedule(schedule);

        // El insert del intento debe fallar aquí, antes del commit y de invocar al proveedor.
        entityManager.flush();
        return WriteResult.APPLIED;
    }

    /** Schedule bloqueado si sigue reclamado y nadie más ha añadido intentos. */
    private Optional<NotificationSchedule> lockOwnedSchedule(UUID scheduleId, int expectedAttemptCount) {
        Optional<NotificationScheduleEntity> entity = scheduleRepository.findByIdForUpdate(scheduleId);

        if (entity.isEmpty() || entity.get().getStatus() != ScheduleStatus.PROCESSING) {
            return Optional.empty();
        }

        if (attemptRepository.countByNotificationId(entity.get().getNotificationId()) != expectedAttemptCount) {
            return Optional.empty();
        }

        return Optional.of(scheduleMapper.toDomain(entity.get()));
    }

    /**
     * Sin propiedad, el resultado del intento propio aún se registra si sigue en PROCESSING: es
     * un hecho de este trabajador y no pisa estado más nuevo. Notificación y schedule no se tocan.
     */
    private void recordOwnAttemptOnly(NotificationAttempt resolvedAttempt) {
        if (resolvedAttempt == null) {
            return;
        }

        attemptRepository.findByIdForUpdate(resolvedAttempt.getId())
                .filter(entity -> entity.getStatus() == AttemptStatus.PROCESSING)
                .ifPresent(entity -> attemptRepository.save(attemptMapper.toEntity(resolvedAttempt)));
    }

    private void closeScheduleOfSettledNotification(
            Notification notification,
            NotificationSchedule schedule,
            Instant now
    ) {
        switch (notification.getStatus()) {
            case ACCEPTED, DELIVERED -> schedule.markSent(now);
            case CANCELLED -> schedule.cancel(now);
            case EXPIRED -> schedule.markExpired(now);
            default -> schedule.markFailed(now);
        }

        saveSchedule(schedule);

        if (notification.redactSensitiveVariables()) {
            saveNotification(notification);
        }
    }

    private void saveNotification(Notification notification) {
        notificationRepository.save(notificationMapper.toEntity(notification));
    }

    private void saveSchedule(NotificationSchedule schedule) {
        scheduleRepository.save(scheduleMapper.toEntity(schedule));
    }
}
