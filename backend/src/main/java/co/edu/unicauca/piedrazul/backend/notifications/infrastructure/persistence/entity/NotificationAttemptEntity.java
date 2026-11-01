package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
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
        name = "notification_attempts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_notification_attempts_number",
                        columnNames = {"notification_id", "channel", "attempt_number"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_notification_attempts_notification_channel",
                        columnList = "notification_id, channel"
                ),
                @Index(
                        name = "idx_notification_attempts_schedule",
                        columnList = "schedule_id"
                )
        }
)
public class NotificationAttemptEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            updatable = false
    )
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "schedule_id", nullable = false)
    private UUID scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "channel",
            nullable = false,
            length = 20
    )
    private NotificationChannel channel;

    @Column(
            name = "provider_name",
            length = 100
    )
    private String providerName;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private AttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "failure_type",
            length = 20
    )
    private FailureType failureType;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}