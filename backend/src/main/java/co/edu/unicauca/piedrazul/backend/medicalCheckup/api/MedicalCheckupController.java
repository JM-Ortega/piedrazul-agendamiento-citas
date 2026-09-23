package co.edu.unicauca.piedrazul.backend.medicalCheckup.api;

import co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop.Auditable;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.MedicalCheckupExternalService;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.intput.CheckupUpdateRequest;
import co.edu.unicauca.piedrazul.backend.medicalCheckup.api.dto.output.MedicalCheckupResponse;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.shared.pagination.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Controles Médicos", description = "Operaciones de consulta y actualización de controles médicos")
@RestController
@RequestMapping("/api/medical-check-up")
@PreAuthorize("hasRole('DOCTOR')")
public class MedicalCheckupController {
    private final MedicalCheckupExternalService service;

    public MedicalCheckupController(MedicalCheckupExternalService service) {
        this.service = service;
    }

    @GetMapping("/patient/{idPatient}")
    @Auditable(
            action = AuditAction.HISTORIA_CLINICA_CONSULTADA,
            targetEntityType = "HistoriaClinica",
            targetIdExpression = "#idPatient"
    )
    @Operation(summary = "Obtener historial clínico de un paciente", description = "Devuelve una página con los controles médicos previos de un paciente. Por defecto, ordena mostrando los controles más recientes primero.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Historial clínico obtenido correctamente"),
            @ApiResponse(responseCode = "400", description = "El identificador del paciente es inválido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar historiales clínicos")
    })
    public ResponseEntity<PageResponse<MedicalCheckupResponse>> getByPatient(
            @Parameter(description = "Identificador único (UUID) del paciente a consultar")
            @PathVariable @NotNull(message = "El id del paciente a consultar es obligatorio") UUID idPatient,

            @Parameter(description = "Parámetros de paginación y ordenamiento. Por defecto: página 0, tamaño 5, ordenado por fecha de atención descendente.")
            @PageableDefault(page = 0, size = 5, sort = "attendedAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Page<MedicalCheckupResponse> history = service.getHistoryByPatient(idPatient, pageable);

        return ResponseEntity.ok(PageResponse.from(history));
    }

    @PostMapping("/updateCheckup/{idCheckUp}")
    @Auditable(
            action = AuditAction.HISTORIA_CLINICA_CONSULTADA,
            targetEntityType = "HistoriaClinica",
            targetIdExpression = "#idCheckUp"
    )
    @Operation(summary = "Actualizar descripción de un control médico",
            description = "Permite editar y actualizar la descripción o hallazgos de un control médico existente." +
                    " Registra la modificación en la auditoría del sistema.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Control médico actualizado correctamente"),
            @ApiResponse(responseCode = "400", description = "Datos de actualización inválidos"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para modificar este registro"),
            @ApiResponse(responseCode = "404", description = "Control médico no encontrado")
    })
    public ResponseEntity<MedicalCheckupResponse> updateCheckup(
            @Parameter(description = "Identificador único (UUID) del control médico a actualizar")
            @PathVariable @NotNull(message = "El id del control medico es obligatorio") UUID idCheckUp,

            @Parameter(description = "Nueva descripción para el control médico")
            @RequestBody @Valid CheckupUpdateRequest request) {
        MedicalCheckupResponse response = service.updateCheckUp(idCheckUp, request);
        return ResponseEntity.ok(response);
    }
}