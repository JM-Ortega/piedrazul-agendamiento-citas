package co.edu.unicauca.piedrazul.backend.clinicalHistory.api;

import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.input.ClinicalHistoryRequest;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.output.ClinicalHistoryResponse;
import org.springframework.http.HttpStatus;
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

    //Registrar una historia clínica
    @PostMapping
    public ResponseEntity<ClinicalHistoryResponse> register(
            @RequestBody ClinicalHistoryRequest request) {

        ClinicalHistoryResponse response = service.registerClinicalHistory(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    //Obtener historial clínico de un paciente
    @GetMapping("/patient/{idPatient}")
    public ResponseEntity<List<ClinicalHistoryResponse>> getByPatient(
            @PathVariable UUID idPatient) {

        List<ClinicalHistoryResponse> history = service.getHistoryByPatient(idPatient);
        return ResponseEntity.ok(history);
    }
}