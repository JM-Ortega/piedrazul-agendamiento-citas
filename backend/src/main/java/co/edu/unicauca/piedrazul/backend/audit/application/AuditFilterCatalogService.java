package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogEntry;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModuleCatalogEntry;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import org.jmolecules.ddd.annotation.Service;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;

@Service
@Component
public class AuditFilterCatalogService {

    private final AuditActionCatalogRepository repository;

    public AuditFilterCatalogService(AuditActionCatalogRepository repository) {
        this.repository = repository;
    }

    /** Módulos y acciones ordenados por nombre; las acciones, primero por el nombre de su módulo. */
    public AuditFilterCatalog filters() {
        var modules = repository.findAllModules().stream()
                .sorted(Comparator.comparing(AuditModuleCatalogEntry::name))
                .toList();
        var actions = repository.findAll().stream()
                .sorted(Comparator.comparing(AuditActionCatalogEntry::moduleName)
                        .thenComparing(AuditActionCatalogEntry::name))
                .toList();
        var outcomes = Arrays.stream(AuditOutcome.values())
                .map(o -> new AuditFilterCatalog.Option(o.name(), o.displayName()))
                .toList();
        return new AuditFilterCatalog(modules, actions, outcomes,
                AuditEventCriteria.MAX_RANGE_DAYS, AuditEventCriteria.MAX_SEARCH_LENGTH);
    }
}
