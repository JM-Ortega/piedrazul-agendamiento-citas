package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.api.dto.AuditActionCatalogResponse;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditActionCatalogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/audit/catalog/actions")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class AuditActionCatalogController {

    private final AuditActionCatalogService service;

    public AuditActionCatalogController(AuditActionCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public List<AuditActionCatalogResponse> getActions() {
        return service.listAll().stream()
                .map(e -> new AuditActionCatalogResponse(e.code().name(), e.name(), e.moduleCode(), e.moduleName()))
                .toList();
    }
}