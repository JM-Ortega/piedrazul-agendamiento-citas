package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.api.dto.AuditEventResponse;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditEventCriteria;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditQueryService;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
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
public class AuditController {

    private final AuditQueryService queryService;
    private final AuditEventMapper mapper;

    public AuditController(AuditQueryService queryService, AuditEventMapper mapper) {
        this.queryService = queryService;
        this.mapper = mapper;
    }

    /**
     * Lista los registros de auditoría, del más reciente al más antiguo. Todos los filtros son
     * opcionales; si se envían {@code from} y {@code to}, el rango no puede superar 90 días.
     * {@code search} busca por el nombre o el usuario (número de documento) de quien ejecutó la
     * acción; cada palabra debe aparecer, en cualquier orden. Las fechas van en formato ISO con zona,
     * por ejemplo {@code 2026-09-26T00:00:00-05:00}.
     */
    @GetMapping
    public ResponseEntity<PageResponse<AuditEventResponse>> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditOutcome outcome,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String targetEntityType,
            @RequestParam(required = false) String targetEntityId,
            @RequestParam(defaultValue = "0") int page,
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
