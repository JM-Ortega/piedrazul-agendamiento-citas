package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.entity;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
        name = "notifications",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_notifications_idempotency_key",
                        columnNames = "idempotency_key"
                )
        }
)
public class NotificationEntity {

    @Id
    @Column(
            name = "id_notification",
            nullable = false,
            updatable = false
    )
    private UUID idNotification;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "type",
            nullable = false,
            length = 80
    )
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "aggregate_type",
            nullable = false,
            length = 80
    )
    private AggregateType aggregateType;

    @Column(
            name = "aggregate_id",
            nullable = false
    )
    private UUID aggregateId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "recipient_type",
            nullable = false,
            length = 40
    )
    private RecipientType recipientType;

    @Column(
            name = "recipient_name",
            nullable = false
    )
    private String recipientName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "recipient_phone_encrypted")
    private String recipientPhoneE164;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "recipient_email_encrypted")
    private String recipientEmail;

    @Column(
            name = "recipient_locale",
            nullable = false,
            length = 20
    )
    private String recipientLocale;

    @Column(name = "recipient_contact_masked")
    private String recipientContactMasked;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "channel_preference_json",
            columnDefinition = "jsonb"
    )
    private ChannelPreference channelPreference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "variables_json",
            columnDefinition = "jsonb",
            nullable = false
    )
    private Map<String, String> variables;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 40
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