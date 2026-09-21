package co.edu.unicauca.piedrazul.backend.medicalCheckup.api;

import co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop.Auditable;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.MedicalCheckupExternalService;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.intput.CheckupUpdateRequest;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.output.MedicalCheckupResponse;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;
import jakarta.validation.Valid;
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
@RequestMapping("/api/medical-check-up")
@PreAuthorize("hasRole('DOCTOR')")
public class MedicalCheckupController {
    private final MedicalCheckupExternalService service;

    public MedicalCheckupController(MedicalCheckupExternalService service) {
        this.service = service;
    }

    //Obtener historial clínico de un paciente
    @GetMapping("/patient/{idPatient}")
    @Auditable(
            action = AuditAction.HISTORIA_CLINICA_CONSULTADA,
            targetEntityType = "HistoriaClinica",
            targetIdExpression = "#idPatient"
    )
    public ResponseEntity<PageResponse<MedicalCheckupResponse>> getByPatient(
            @PathVariable @NotNull(message = "El id del paciente a consultar es obligatorio") UUID idPatient,
            @RequestParam(defaultValue = "0") @Min(0) @NotNull(message = "El número de pagina es obligatorio") int page) {

        Pageable pageable = PageRequest.of(page, 5);

        Page<MedicalCheckupResponse> history =
                service.getHistoryByPatient(idPatient, pageable);

        return ResponseEntity.ok(PageResponse.from(history));
    }

    //permite editar la descripcion de un control medico.
    @PostMapping("/updateCheckup/{idCheckUp}")
    @Auditable(
            action = AuditAction.HISTORIA_CLINICA_CONSULTADA,
            targetEntityType = "HistoriaClinica",
            targetIdExpression = "#idClinicalHistory"
    )
    public ResponseEntity<MedicalCheckupResponse> updateCheckup(
            @PathVariable @NotNull(message = "El id del control medico es obligatorio") UUID idCheckUp,
            @RequestBody @Valid CheckupUpdateRequest request) {
        MedicalCheckupResponse response = service.updateCheckUp(idCheckUp, request);
        return ResponseEntity.ok(response);
    }
}