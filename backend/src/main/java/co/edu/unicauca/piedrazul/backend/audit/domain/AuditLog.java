package co.edu.unicauca.piedrazul.backend.audit.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action; // qué pasó

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditModule module; // en qué módulo

    @Column(nullable = false)
    private String entityId;  // ID del recurso afectado

    @Column(nullable = false)
    private String performedBy; // username de quien lo hizo

    @Column(nullable = false)
    private LocalDateTime performedAt; // cuándo

    protected AuditLog() {}

    public AuditLog(AuditAction action, AuditModule module,
                    String entityId, String performedBy) {
        this.action      = action;
        this.module      = module;
        this.entityId    = entityId;
        this.performedBy = performedBy;
        this.performedAt = LocalDateTime.now();
    }
}
