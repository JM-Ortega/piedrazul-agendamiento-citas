package co.edu.unicauca.piedrazul.backend.audit.domain;

/**
 * Puerto de salida. La implementación (JPA) vive en infrastructure.
 * El dominio y application solo conocen esta interfaz.
 */
public interface AuditEventRepository {

    void save(AuditEvent event);

    /** Ordenado siempre del más reciente al más antiguo. */
    AuditEventPage findByCriteria(AuditEventQuery query);
}
