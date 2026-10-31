package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditActionJpaRepository extends JpaRepository<AuditActionJpaEntity, String> {
    @EntityGraph(attributePaths = "module") // evita N+1 al leer module.getName()
    List<AuditActionJpaEntity> findAll();
}
