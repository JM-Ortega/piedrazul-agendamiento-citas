package co.edu.unicauca.piedrazul.backend.audit.api.dto;

import java.time.Instant;

public record AuditEventResponse(
        String actorUsername,
        String actorRole,
        String action,
        String targetEntityType,
        String targetEntityId,
        String outcome,
        Instant timestamp,
        String correlationId
) { }