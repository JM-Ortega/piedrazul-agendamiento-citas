package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.audit.exception.InvalidAuditCriteriaException;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Criterios de la consulta de auditoría, ya validados.
 *
 * <p>Las fechas son días completos, sin hora, y las dos se incluyen: {@code from} y {@code to}
 * iguales buscan un solo día. Son opcionales. Si vienen las dos, {@code from} no puede ser
 * posterior a {@code to} y el rango no puede abarcar más de {@value #MAX_RANGE_DAYS} días,
 * contando ambos extremos. Si viene solo una, el rango queda abierto por el otro extremo y no
 * se aplica ese tope.
 */
public record AuditEventCriteria(
        LocalDate from,
        LocalDate to,
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
    public static final int MAX_RANGE_DAYS = 90;
    public static final int MAX_SEARCH_LENGTH = 100;

    public AuditEventCriteria {
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw new InvalidAuditCriteriaException("La fecha inicial no puede ser posterior a la fecha final");
            }
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
                throw new InvalidAuditCriteriaException(
                        "El rango de fechas no puede superar " + MAX_RANGE_DAYS + " días");
            }
        }

        search = normalizeSearch(search);
        moduleCode = blankToNull(moduleCode);
        actorId = blankToNull(actorId);
        targetEntityType = blankToNull(targetEntityType);
        targetEntityId = blankToNull(targetEntityId);

        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 50; // límite defensivo
    }

    private static String normalizeSearch(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.replaceAll("\\s+", " ");
        if (normalized.length() > MAX_SEARCH_LENGTH) {
            throw new InvalidAuditCriteriaException(
                    "La búsqueda no puede superar " + MAX_SEARCH_LENGTH + " caracteres");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
