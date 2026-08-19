package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

import java.time.Instant;

public record AuditEventCriteria(
        String actorUsername,
        AuditAction action,
        String targetEntityType,
        String targetEntityId,
        Instant from,
        Instant to,
        int page,
        int size
) {
    public AuditEventCriteria {
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 50; // límite defensivo
    }
}