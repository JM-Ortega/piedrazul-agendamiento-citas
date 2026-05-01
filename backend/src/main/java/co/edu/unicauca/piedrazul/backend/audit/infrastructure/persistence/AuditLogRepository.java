package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditLog;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    // para el endpoint de consulta del administrador
    List<AuditLog> findByModuleOrderByPerformedAtDesc(AuditModule module);

    List<AuditLog> findByPerformedByOrderByPerformedAtDesc(String username);

    List<AuditLog> findByPerformedAtBetweenOrderByPerformedAtDesc(
            LocalDateTime from, LocalDateTime to
    );
}
