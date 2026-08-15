package co.edu.unicauca.piedrazul.backend.audit.api.dto;


public record AuditActionCatalogResponse(
        String code,
        String name,
        String moduleCode,
        String moduleName
) { }