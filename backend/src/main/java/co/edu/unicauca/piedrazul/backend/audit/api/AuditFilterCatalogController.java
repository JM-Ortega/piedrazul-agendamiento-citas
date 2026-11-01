package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.api.dto.AuditActionCatalogResponse;
import co.edu.unicauca.piedrazul.backend.audit.api.dto.AuditFilterCatalogResponse;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditFilterCatalog;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditFilterCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/audit/catalog/filters")
@PreAuthorize("hasRole('AUDITOR')")
@Tag(name = "Auditoría", description = "Consulta de la bitácora de auditoría del sistema. Solo el rol AUDITOR")
public class AuditFilterCatalogController {

    private final AuditFilterCatalogService service;

    public AuditFilterCatalogController(AuditFilterCatalogService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Obtener las opciones de los filtros de auditoría",
            description = "Devuelve, en una sola llamada, los valores posibles de los filtros de valor cerrado de "
                    + "`GET /api/audit` (`moduleCode`, `action` y `outcome`), cada uno con su "
                    + "nombre para mostrar, y los límites de los filtros abiertos: días máximos entre `from` y `to` "
                    + "y caracteres máximos de `search`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Opciones obtenidas correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos: solo el rol AUDITOR")
    })
    public AuditFilterCatalogResponse getFilters() {
        AuditFilterCatalog catalog = service.filters();
        return new AuditFilterCatalogResponse(
                catalog.modules().stream()
                        .map(m -> new AuditFilterCatalogResponse.Option(m.code(), m.name()))
                        .toList(),
                catalog.actions().stream()
                        .map(e -> new AuditActionCatalogResponse(
                                e.code().name(), e.name(), e.moduleCode(), e.moduleName()))
                        .toList(),
                toOptions(catalog.outcomes()),
                catalog.maxRangeDays(),
                catalog.maxSearchLength());
    }

    private static List<AuditFilterCatalogResponse.Option> toOptions(List<AuditFilterCatalog.Option> options) {
        return options.stream()
                .map(o -> new AuditFilterCatalogResponse.Option(o.code(), o.name()))
                .toList();
    }
}
