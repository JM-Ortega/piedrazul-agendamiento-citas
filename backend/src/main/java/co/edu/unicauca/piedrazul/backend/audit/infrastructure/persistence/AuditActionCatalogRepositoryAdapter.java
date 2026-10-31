package co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogEntry;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModuleCatalogEntry;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class AuditActionCatalogRepositoryAdapter implements AuditActionCatalogRepository {

    private final AuditActionJpaRepository jpaRepository;
    private final AuditModuleJpaRepository moduleJpaRepository;

    public AuditActionCatalogRepositoryAdapter(AuditActionJpaRepository jpaRepository,
                                               AuditModuleJpaRepository moduleJpaRepository) {
        this.jpaRepository = jpaRepository;
        this.moduleJpaRepository = moduleJpaRepository;
    }

    @Override
    public List<AuditActionCatalogEntry> findAll() {
        return jpaRepository.findAll().stream()
                .map(e -> new AuditActionCatalogEntry(
                        AuditAction.valueOf(e.getCode()),
                        e.getName(),
                        e.getModule().getCode(),
                        e.getModule().getName()
                ))
                .toList();
    }

    @Override
    public List<AuditModuleCatalogEntry> findAllModules() {
        return moduleJpaRepository.findAll().stream()
                .map(m -> new AuditModuleCatalogEntry(m.getCode(), m.getName()))
                .toList();
    }
}
