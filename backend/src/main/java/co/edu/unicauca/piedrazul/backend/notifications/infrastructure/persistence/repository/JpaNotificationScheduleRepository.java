package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.repository;

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

    /**
     * Con intentos en curso, manda el más reciente; sin ellos, el reclamo. Sin bloqueo: la decisión
     * real se toma después, bajo el bloqueo de cada schedule.
     */
    @Query(value = """
            SELECT s.id
            FROM notification_schedule s
            LEFT JOIN LATERAL (
                SELECT MAX(a.updated_at) AS latest
                FROM notification_attempts a
                WHERE a.notification_id = s.notification_id
                  AND a.status = 'PROCESSING'
            ) in_flight ON TRUE
            WHERE COALESCE(
                in_flight.latest < :staleBefore,
                s.status = 'PROCESSING' AND s.processing_started_at < :staleBefore
            )
            ORDER BY COALESCE(in_flight.latest, s.processing_started_at) ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findInterruptedIds(
            @Param("staleBefore") Instant staleBefore,
            @Param("limit") int limit
    );

    /** Bloquea la fila del schedule: serializa el despacho y la recuperación del mismo trabajo. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM NotificationScheduleEntity s WHERE s.id = :id")
    Optional<NotificationScheduleEntity> findByIdForUpdate(@Param("id") UUID id);
}
