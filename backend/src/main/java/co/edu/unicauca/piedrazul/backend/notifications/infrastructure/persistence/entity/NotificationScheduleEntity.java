package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ScheduleStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "notification_schedule",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_notification_schedule_notification",
                        columnNames = "notification_id"
                )
        }
)
public class NotificationScheduleEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ScheduleStatus status;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}