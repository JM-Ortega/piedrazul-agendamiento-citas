package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event", schema = "piedrazul")
public class AuditEventJpaEntity {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_username", nullable = false, length = 100)
    private String actorUsername;

    @Column(name = "actor_role", length = 50)
    private String actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_code", nullable = false, length = 50)
    private AuditAction action;

    @Column(name = "target_entity_type", length = 100)
    private String targetEntityType;

    @Column(name = "target_entity_id", length = 100)
    private String targetEntityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuditOutcome outcome;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "before_state", columnDefinition = "text")
    private String beforeState;

    @Column(name = "after_state", columnDefinition = "text")
    private String afterState;

    protected AuditEventJpaEntity() { }

    public AuditEventJpaEntity(UUID id, Instant occurredAt, String actorUsername, String actorRole,
                               AuditAction action, String targetEntityType, String targetEntityId,
                               AuditOutcome outcome, String correlationId,
                               String beforeState, String afterState) {
        this.id = id;
        this.occurredAt = occurredAt;
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
    public Instant getOccurredAt() { return occurredAt; }
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