package co.edu.unicauca.piedrazul.backend.clinicalHistory.api;

import co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop.Auditable;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.application.ClinicalHistoryExternalServiceImpl;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/clinical-history")
@PreAuthorize("hasRole('DOCTOR')")
public class ClinicalHistoryController {
    private final ClinicalHistoryExternalServiceImpl service;

    public ClinicalHistoryController(ClinicalHistoryExternalServiceImpl service) {
        this.service = service;
    }

    //Obtener historial clínico de un paciente
    @GetMapping("/patient/{idPatient}")
    @Auditable(
            action = AuditAction.HISTORIA_CLINICA_CONSULTADA,
            targetEntityType = "HistoriaClinica",
            targetIdExpression = "#idPatient"
    )
    public ResponseEntity<PageResponse<ClinicalHistoryResponse>> getByPatient(
            @PathVariable @NotNull(message = "El id del paciente a consultar es obligatorio") UUID idPatient,
            @RequestParam(defaultValue = "0") @Min(0) @NotNull(message = "El número de pagina es obligatorio") int page) {

        Pageable pageable = PageRequest.of(page, 5);

        Page<ClinicalHistoryResponse> history =
                service.getHistoryByPatient(idPatient, pageable);

        return ResponseEntity.ok(PageResponse.from(history));
    }
}