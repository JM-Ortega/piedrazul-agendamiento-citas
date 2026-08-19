package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "audit_module", schema = "piedrazul")
public class AuditModuleJpaEntity {

    @Id
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    protected AuditModuleJpaEntity() { }

    public String getCode() { return code; }
    public String getName() { return name; }
}
