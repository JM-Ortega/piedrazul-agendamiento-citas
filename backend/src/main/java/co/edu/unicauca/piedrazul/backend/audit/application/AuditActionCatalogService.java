package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogEntry;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogRepository;
import org.jmolecules.ddd.annotation.Service;
import org.springframework.stereotype.Component;

import java.util.List;

@Service
@Component
public class AuditActionCatalogService {

    private final AuditActionCatalogRepository repository;

    public AuditActionCatalogService(AuditActionCatalogRepository repository) {
        this.repository = repository;
    }

    public List<AuditActionCatalogEntry> listAll() {
        return repository.findAll();
    }
}