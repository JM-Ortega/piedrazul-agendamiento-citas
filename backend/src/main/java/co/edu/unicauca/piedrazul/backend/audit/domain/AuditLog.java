package co.edu.unicauca.piedrazul.backend.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_log", schema = "piedrazul")
public class AuditLog {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_code", nullable = false, length = 60)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "module_code", nullable = false, length = 40)
    private AuditModule module;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "performed_by", nullable = false)
    private UUID performedBy;

    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    protected AuditLog() {
    }

    public AuditLog(AuditAction action, AuditModule module, UUID entityId, UUID performedBy) {
        this.id = UUID.randomUUID();
        this.action = action;
        this.module = module;
        this.entityId = entityId;
        this.performedBy = performedBy;
        this.performedAt = LocalDateTime.now();
    }
}
