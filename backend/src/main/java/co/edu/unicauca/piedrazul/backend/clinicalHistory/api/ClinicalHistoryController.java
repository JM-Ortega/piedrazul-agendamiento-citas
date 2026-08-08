package co.edu.unicauca.piedrazul.backend.clinicalHistory.api;

import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/clinical-history")
public class ClinicalHistoryController {
    private final ClinicalHistoryExternalService service;

    public ClinicalHistoryController(ClinicalHistoryExternalService service) {
        this.service = service;
    }

    //Obtener historial clínico de un paciente
    @GetMapping("/patient/{idPatient}")
    public ResponseEntity<PageResponse<ClinicalHistoryResponse>> getByPatient(
            @PathVariable @NotNull(message = "El id del paciente a consultar es obligatorio") UUID idPatient,
            @RequestParam(defaultValue = "0") @Min(0) @NotNull(message = "El número de pagina es obligatorio") int page) {

        Pageable pageable = PageRequest.of(page, 5);

        Page<ClinicalHistoryResponse> history =
                service.getHistoryByPatient(idPatient, pageable);

        return ResponseEntity.ok(PageResponse.from(history));
    }
}