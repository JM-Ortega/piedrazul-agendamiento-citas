package co.edu.unicauca.piedrazul.backend.clinicalHistory.api;

import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    public ResponseEntity<List<ClinicalHistoryResponse>> getByPatient(
            @PathVariable UUID idPatient) {

        List<ClinicalHistoryResponse> history = service.getHistoryByPatient(idPatient);
        return ResponseEntity.ok(history);
    }
}