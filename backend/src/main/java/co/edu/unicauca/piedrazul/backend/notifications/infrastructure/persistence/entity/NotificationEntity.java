package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.converter.ChannelPreferenceConverter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.converter.VariablesConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "notification",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_notification_idempotency_key",
                        columnNames = "idempotency_key"
                )
        },
        indexes = {
                @Index(name = "idx_notification_type", columnList = "type_code"),
                @Index(name = "idx_notification_recipient", columnList = "recipient_id"),
                @Index(name = "idx_notification_aggregate", columnList = "aggregate_id, aggregate_type")
        }
)
public class NotificationEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            updatable = false
    )
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "type_code",
            nullable = false,
            length = 60
    )
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "aggregate_type",
            nullable = false,
            length = 40
    )
    private AggregateType aggregateType;

    @Column(
            name = "aggregate_id",
            nullable = false
    )
    private UUID aggregateId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(
            name = "recipient_name",
            nullable = false,
            length = 200
    )
    private String recipientName;

    @Column(name = "recipient_phone", length = 20)
    private String recipientPhoneE164;

    @Column(name = "recipient_email", length = 255)
    private String recipientEmail;

    @Column(
            name = "recipient_locale",
            length = 10
    )
    private String recipientLocale;

    @Convert(converter = ChannelPreferenceConverter.class)
    @Column(
            name = "channel_preference_json",
            columnDefinition = "TEXT"
    )
    private ChannelPreference channelPreference;

    @Convert(converter = VariablesConverter.class)
    @Column(
            name = "variables_json",
            columnDefinition = "TEXT",
            nullable = false
    )
    private Map<String, String> variables;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20
    )
    private NotificationStatus status;

    @Column(
            name = "idempotency_key",
            nullable = false,
            length = 255
    )
    private String idempotencyKey;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;
}
