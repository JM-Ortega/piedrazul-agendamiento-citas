package co.edu.unicauca.piedrazul.backend.audit.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Un registro de auditoría tal como se muestra en la consulta.
 *
 * <p>{@code actorName} y {@code actorUsername} no se guardan con el registro: se resuelven
 * al consultar, a partir del id de la cuenta ({@code actorId}), con los datos vigentes de la
 * persona. Así, si la persona cambia de nombre o de documento, todos sus registros anteriores
 * lo reflejan. Son nulos cuando la cuenta no tiene persona asociada. El usuario de la cuenta
 * coincide con el número de documento de la persona.
 *
 * <p>{@code action} y {@code outcome} van como texto para que un código que ya no exista en
 * el enum no impida listar el resto. {@code moduleCode} y {@code moduleName} son los del
 * módulo del catálogo al que pertenece {@code action} (ver {@code GET /api/audit/catalog/filters}).
 */
public record AuditEventView(
        UUID id,
        Instant timestamp,
        String actorId,
        String actorRoles,
        String actorName,
        String actorUsername,
        String action,
        String outcome,
        String targetEntityType,
        String targetEntityId,
        String correlationId,
        String moduleCode,
        String moduleName
) {
}
