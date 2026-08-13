package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "audit_action", schema = "piedrazul")
public class AuditActionJpaEntity {

    @Id
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audit_module_code", nullable = false)
    private AuditModuleJpaEntity module;

    protected AuditActionJpaEntity() { }

    public String getCode() { return code; }
    public String getName() { return name; }
    public AuditModuleJpaEntity getModule() { return module; }
}