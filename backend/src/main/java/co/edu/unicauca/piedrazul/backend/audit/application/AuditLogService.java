package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditAction;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditLog;
import co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence.AuditLogRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModule;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void register(AuditAction action, AuditModule module, UUID entityId, UUID performedBy) {
        auditLogRepository.save(new AuditLog(
                action,
                module,
                entityId,
                performedBy
        ));
    }
}
