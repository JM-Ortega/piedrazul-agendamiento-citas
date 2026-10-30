package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ScheduleStatus;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationScheduleEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaNotificationScheduleRepository
        extends JpaRepository<NotificationScheduleEntity, UUID> {

    @Query(value = """
            SELECT *
            FROM notification_schedule
            WHERE status = 'PENDING'
              AND COALESCE(next_retry_at, scheduled_at) <= :now
            ORDER BY COALESCE(next_retry_at, scheduled_at) ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<NotificationScheduleEntity> findDuePendingWithLock(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    List<NotificationScheduleEntity> findAllByNotificationIdAndStatusIn(
            UUID notificationId,
            List<ScheduleStatus> statuses
    );

    /** Bloquea la fila del schedule: serializa el despacho y la recuperación del mismo trabajo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM NotificationScheduleEntity s WHERE s.id = :id")
    Optional<NotificationScheduleEntity> findByIdForUpdate(@Param("id") UUID id);
}
