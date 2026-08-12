package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.api.dto.AuditEventResponse;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import org.springframework.stereotype.Component;

@Component
public class AuditEventMapper {
    public AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(
                e.getActorUsername(), e.getActorRole(), e.getAction().name(),
                e.getTargetEntityType(), e.getTargetEntityId(), e.getOutcome().name(),
                e.getTimestamp(), e.getCorrelationId()
        );
        // Nota: beforeState/afterState se omiten deliberadamente del
        // response por defecto (puede contener datos clínicos sensibles).
    }
}
