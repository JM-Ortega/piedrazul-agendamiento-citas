package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.api.dto.AuditEventResponse;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditEventCriteria;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditQueryService;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Auditoría", description = "Consulta de la bitácora de auditoría del sistema. Solo administradores")
public class AuditController {

    private final AuditQueryService queryService;
    private final AuditEventMapper mapper;

    public AuditController(AuditQueryService queryService, AuditEventMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "Listar registros de auditoría",
            description = "Devuelve una página de registros de auditoría, del más reciente al más antiguo. "
                    + "Todos los filtros son opcionales y se combinan entre sí; sin ninguno se devuelve todo el historial. "
                    + "Si se envían `from` y `to`, el rango no puede superar 90 días; con una sola fecha el rango queda "
                    + "abierto por el otro extremo. `search` busca por el nombre completo o por el usuario (número de "
                    + "documento) de quien ejecutó la acción, sin distinguir mayúsculas ni tildes. El nombre y el usuario "
                    + "son los datos vigentes de la persona dueña de la cuenta `actorId`, por lo que se actualizan "
                    + "también en los registros antiguos; son nulos si la cuenta no tiene persona asociada. "
                    + "Los estados antes y después del cambio no se exponen.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Registros obtenidos correctamente"),
            @ApiResponse(responseCode = "400", description = "Filtros inválidos: fecha mal formada, rango invertido o "
                    + "mayor a 90 días, acción o resultado inexistentes, o búsqueda de más de 100 caracteres"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos: solo el rol ADMIN puede consultar la auditoría")
    })
    public ResponseEntity<PageResponse<AuditEventResponse>> search(
            @Parameter(description = "Inicio del rango de fechas, inclusive. ISO-8601 con zona horaria.",
                    example = "2026-09-26T00:00:00-05:00")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Fin del rango de fechas, inclusive. ISO-8601 con zona horaria.",
                    example = "2026-09-26T23:59:59-05:00")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "Acción auditada. El listado completo, con sus nombres, está en "
                    + "`GET /api/audit/catalog/actions`.")
            @RequestParam(required = false) AuditAction action,
            @Parameter(description = "Resultado de la acción: `EXITOSO`, `FALLIDO` (falló dentro de la operación) "
                    + "o `DENEGADO` (rechazada por falta de rol).")
            @RequestParam(required = false) AuditOutcome outcome,
            @Parameter(description = "Palabras a buscar en el nombre completo o el usuario (número de documento) de "
                    + "quien ejecutó la acción. Coincidencia parcial; cada palabra debe aparecer, en cualquier "
                    + "orden. Hasta 100 caracteres.",
                    example = "jose garcia")
            @RequestParam(required = false) String search,
            @Parameter(description = "Id de la cuenta de Keycloak de quien ejecutó la acción. Coincidencia exacta.",
                    example = "3f2c1d0e-8a41-4b6e-9d57-2b1e6f0a7c11")
            @RequestParam(required = false) String actorId,
            @Parameter(description = "Tipo del objeto afectado. Coincidencia exacta.", example = "Paciente")
            @RequestParam(required = false) String targetEntityType,
            @Parameter(description = "Id del objeto afectado. Coincidencia exacta.",
                    example = "9b8f2d64-1c3e-4f0a-a7d5-5e6b8c9d0e12")
            @RequestParam(required = false) String targetEntityId,
            @Parameter(description = "Número de página, desde 0.", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Registros por página, de 1 a 200. Fuera de ese rango se usa 50.", example = "20")
            @RequestParam(defaultValue = "20") int size) {

        var criteria = new AuditEventCriteria(from, to, action, outcome, search, actorId,
                targetEntityType, targetEntityId, page, size);
        var result = queryService.search(criteria);
        var content = result.content().stream().map(mapper::toResponse).toList();
        var totalPages = result.totalPages();
        var first = result.page() == 0;
        var last = result.page() >= totalPages - 1;
        var empty = content.isEmpty();
        return ResponseEntity.ok(PageResponse.of(
                content,
                result.page(),
                result.size(),
                result.totalElements(),
                totalPages,
                first,
                last,
                empty));
    }
}
