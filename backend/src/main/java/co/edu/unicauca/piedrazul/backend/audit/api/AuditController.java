package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.api.dto.AuditEventResponse;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditEventCriteria;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditQueryService;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import jakarta.validation.constraints.Min;
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

    @GetMapping
    public ResponseEntity<PageResponse<AuditEventResponse>> search(
            @RequestParam(required = false) String actorUsername,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String targetEntityType,
            @RequestParam(required = false) String targetEntityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var criteria = new AuditEventCriteria(actorUsername, action, targetEntityType, targetEntityId, from, to, page, size);
        var result = queryService.search(criteria);
        var content = result.content().stream().map(mapper::toResponse).toList();
        return ResponseEntity.ok(PageResponse.of(content, result.page(), result.totalPages(), result.totalElements()));
    }
}