package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface AuditEventJpaRepository extends JpaRepository<AuditEventJpaEntity, UUID>,
        JpaSpecificationExecutor<AuditEventJpaEntity> {
}