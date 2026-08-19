package co.edu.unicauca.piedrazul.backend.audit.domain;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

import java.time.Instant;
import java.util.List;

/**
 * Puerto de salida. La implementación (JPA) vive en infrastructure.
 * El dominio y application solo conocen esta interfaz.
 */
public interface AuditEventRepository {

    void save(AuditEvent event);

    AuditEventPage findByCriteria(
            String actorUsername,
            AuditAction action,
            String targetEntityType,
            String targetEntityId,
            Instant from,
            Instant to,
            int page,
            int size
    );
}
