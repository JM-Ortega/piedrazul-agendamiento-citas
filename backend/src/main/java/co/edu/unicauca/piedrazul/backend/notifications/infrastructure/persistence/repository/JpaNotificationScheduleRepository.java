package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ScheduleStatus;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity.NotificationScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JpaNotificationScheduleRepository
        extends JpaRepository<NotificationScheduleEntity, UUID> {

    @Query(value = """
            SELECT *
            FROM notification_schedules
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
}