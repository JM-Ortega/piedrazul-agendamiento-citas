package co.edu.unicauca.piedrazul.backend.audit.domain;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

/** Proyección de solo lectura de piedrazul.audit_action + audit_module. */
public record AuditActionCatalogEntry(
        AuditAction code, String name, String moduleCode, String moduleName
) { }