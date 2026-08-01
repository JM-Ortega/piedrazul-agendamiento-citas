package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
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
        name = "notification_delivery_events",
        indexes = {
                @Index(
                        name = "idx_delivery_event_attempt",
                        columnList = "attempt_id"
                ),
                @Index(
                        name = "idx_delivery_event_provider_message",
                        columnList = "provider_name, provider_message_id"
                )
        }
)
public class DeliveryEventEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    @Column(name = "provider_name", nullable = false, length = 100)
    private String providerName;

    @Column(name = "provider_message_id", nullable = false)
    private String providerMessageId;

    @Column(name = "provider_event_id")
    private String providerEventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "raw_status", nullable = false)
    private String rawStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_status", nullable = false, length = 40)
    private AttemptStatus normalizedStatus;

    @Column(name = "payload_json", columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}