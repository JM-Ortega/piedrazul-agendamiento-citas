package co.edu.unicauca.piedrazul.backend.audit.domain;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

import java.time.Instant;

/**
 * Filtros de la consulta de auditoría. Todos son opcionales: sin ninguno se devuelve todo,
 * de lo más reciente a lo más antiguo.
 *
 * <p>{@code search} busca por nombre completo o número de documento de quien ejecutó la
 * acción, sin distinguir mayúsculas ni tildes. Cada palabra debe aparecer, en cualquier orden.
 */
public record AuditEventQuery(
        Instant from,
        Instant to,
        AuditAction action,
        AuditOutcome outcome,
        String search,
        String actorId,
        String targetEntityType,
        String targetEntityId,
        int page,
        int size
) {
}
