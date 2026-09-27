package co.edu.unicauca.piedrazul.backend.audit.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un registro de auditoría. {@code actorName} y {@code actorUsername} son los datos vigentes de
 * la persona dueña de la cuenta {@code actorId}; son nulos si la cuenta no tiene persona
 * asociada.
 */
public record AuditEventResponse(
        UUID id,
        Instant timestamp,
        String actorName,
        String actorUsername,
        String actorId,
        List<String> actorRoles,
        String action,
        String outcome,
        String targetEntityType,
        String targetEntityId,
        String correlationId
) { }
