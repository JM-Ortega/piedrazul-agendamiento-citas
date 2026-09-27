package co.edu.unicauca.piedrazul.backend.audit.domain;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

import java.time.Instant;

/**
 * Filtros de la consulta de auditoría. Todos son opcionales: sin ninguno se devuelve todo,
 * de lo más reciente a lo más antiguo.
 *
 * <p>{@code search} busca por nombre completo o número de documento de quien ejecutó la
 * acción, sin distinguir mayúsculas ni tildes. Cada palabra debe aparecer, en cualquier orden.
 *
 * <p>{@code moduleCode} filtra por el módulo al que pertenece la acción (el mismo código que
 * devuelve {@code GET /api/audit/catalog/filters} en {@code modules}), por ejemplo
 * {@code PACIENTES} o {@code USUARIOS}.
 */
public record AuditEventQuery(
        Instant from,
        Instant to,
        AuditAction action,
        String moduleCode,
        AuditOutcome outcome,
        String search,
        String actorId,
        String targetEntityType,
        String targetEntityId,
        int page,
        int size
) {
}
