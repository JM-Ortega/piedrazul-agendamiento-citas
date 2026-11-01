package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditModuleJpaRepository extends JpaRepository<AuditModuleJpaEntity, String> {
}
