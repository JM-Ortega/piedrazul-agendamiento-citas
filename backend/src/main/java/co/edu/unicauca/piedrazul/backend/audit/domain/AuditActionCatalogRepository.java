package co.edu.unicauca.piedrazul.backend.audit.domain;

import java.util.List;

public interface AuditActionCatalogRepository {
    List<AuditActionCatalogEntry> findAll();
}