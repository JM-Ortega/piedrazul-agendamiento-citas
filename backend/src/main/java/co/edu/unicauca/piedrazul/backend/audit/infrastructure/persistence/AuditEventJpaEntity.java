package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event", indexes = {
        @Index(name = "idx_audit_actor_ts", columnList = "actorUsername, timestamp"),
        @Index(name = "idx_audit_target", columnList = "targetEntityType, targetEntityId"),
        @Index(name = "idx_audit_timestamp", columnList = "timestamp")
})
public class AuditEventJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, length = 100)
    private String actorUsername;

    @Column(length = 50)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuditAction action;

    @Column(length = 100)
    private String targetEntityType;

    @Column(length = 100)
    private String targetEntityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditOutcome outcome;

    @Column(length = 100)
    private String correlationId;

    @Lob
    private String beforeState;

    @Lob
    private String afterState;

    protected AuditEventJpaEntity() { } // JPA

    public AuditEventJpaEntity(UUID id, Instant timestamp, String actorUsername, String actorRole,
                               AuditAction action, String targetEntityType, String targetEntityId,
                               AuditOutcome outcome, String correlationId,
                               String beforeState, String afterState) {
        this.id = id;
        this.timestamp = timestamp;
        this.actorUsername = actorUsername;
        this.actorRole = actorRole;
        this.action = action;
        this.targetEntityType = targetEntityType;
        this.targetEntityId = targetEntityId;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.beforeState = beforeState;
        this.afterState = afterState;
    }

    // Solo getters. Sin setters: refuerza inmutabilidad también a nivel JPA.
    public UUID getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getActorUsername() { return actorUsername; }
    public String getActorRole() { return actorRole; }
    public AuditAction getAction() { return action; }
    public String getTargetEntityType() { return targetEntityType; }
    public String getTargetEntityId() { return targetEntityId; }
    public AuditOutcome getOutcome() { return outcome; }
    public String getCorrelationId() { return correlationId; }
    public String getBeforeState() { return beforeState; }
    public String getAfterState() { return afterState; }
}