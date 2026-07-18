package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditLog;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    // para el endpoint de consulta del administrador
    List<AuditLog> findByModuleOrderByPerformedAtDesc(AuditModule module);

    List<AuditLog> findByPerformedByOrderByPerformedAtDesc(UUID performedBy);

    List<AuditLog> findByPerformedAtBetweenOrderByPerformedAtDesc(
            LocalDateTime from, LocalDateTime to
    );
}
