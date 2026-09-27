package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.api.dto.AuditEventResponse;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventView;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class AuditEventMapper {
    public AuditEventResponse toResponse(AuditEventView e) {
        return new AuditEventResponse(
                e.id(), e.timestamp(), e.actorName(), e.actorUsername(), e.actorId(),
                parseRoles(e.actorRoles()), e.action(), e.moduleCode(), e.moduleName(), e.outcome(),
                e.targetEntityType(), e.targetEntityId()
        );
        // Nota: beforeState/afterState se omiten deliberadamente del response (pueden contener
        // datos personales o clínicos sensibles), y correlationId es un dato interno de rastreo,
        // no algo que el frontend necesite.
    }

    /**
     * Los roles se guardan como el texto de una lista ("[ADMIN, DOCTOR]"), o "N/A" cuando no
     * había roles que registrar (acciones anónimas).
     */
    static List<String> parseRoles(String stored) {
        if (stored == null) {
            return List.of();
        }

        String inner = stored.trim();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }

        return Arrays.stream(inner.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty() && !role.equalsIgnoreCase("N/A"))
                .toList();
    }
}
