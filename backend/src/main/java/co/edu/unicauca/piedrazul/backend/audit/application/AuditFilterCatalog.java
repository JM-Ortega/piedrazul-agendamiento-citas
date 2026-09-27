package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogEntry;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModuleCatalogEntry;

import java.util.List;

/**
 * Todo lo que el frontend necesita para armar los filtros de la consulta de auditoría:
 * las opciones de cada filtro de valor cerrado y los límites de los filtros abiertos.
 */
public record AuditFilterCatalog(
        List<AuditModuleCatalogEntry> modules,
        List<AuditActionCatalogEntry> actions,
        List<Option> outcomes,
        List<Option> targetEntityTypes,
        int maxRangeDays,
        int maxSearchLength
) {
    /** Una opción de un filtro: el valor que se envía y el nombre que se muestra. */
    public record Option(String code, String name) { }
}
