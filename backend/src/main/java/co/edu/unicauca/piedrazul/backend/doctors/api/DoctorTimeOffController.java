package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateTimeOffRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.TimeOffResponse;
import co.edu.unicauca.piedrazul.backend.doctors.application.TimeOffService;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DoctorTimeOff;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Gestión de los periodos de descanso de los doctores (rangos de fechas sin agendamiento).
 */
@Tag(name = "Descansos de doctores", description = "Periodos de fechas en los que un doctor no atiende y no se puede agendar con él. "
        + "Un doctor puede tener varios. Solo los gestiona el administrador.")
@RestController
@RequestMapping("/api/doctor/time-off")
@PreAuthorize("hasRole('ADMIN')")
public class DoctorTimeOffController {

    private final TimeOffService timeOffService;

    public DoctorTimeOffController(TimeOffService timeOffService) {
        this.timeOffService = timeOffService;
    }

    /** Registra un descanso. Debe empezar después de la ventana de agendamiento y sin citas en el rango. */
    @PostMapping
    @Operation(summary = "Registrar un descanso de un doctor",
            description = "Crea un periodo de descanso con fechas de inicio y fin inclusivas. Mientras dure, las fechas del rango "
                    + "dejan de estar disponibles para agendar con el doctor. Reglas: el doctor debe estar activo; la fecha de "
                    + "inicio debe ser posterior al último día de su ventana de agendamiento (hoy + bookingWindowWeeks semanas); "
                    + "la fecha de fin no puede pasar de su fecha de fin de vinculación; no puede haber citas agendadas del doctor "
                    + "en el rango; y no puede cruzarse con otro descanso del mismo doctor, incluidos los históricos. "
                    + "El motivo es opcional. Queda registrado en la auditoría.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Descanso registrado correctamente"),
            @ApiResponse(responseCode = "400", description = "Faltan campos obligatorios o el motivo supera los 255 caracteres"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para registrar descansos"),
            @ApiResponse(responseCode = "404", description = "No existe un doctor con el identificador proporcionado"),
            @ApiResponse(responseCode = "409", description = "No se puede registrar: el doctor está inactivo, la fecha de fin es anterior a la de inicio, "
                    + "el inicio cae dentro de la ventana de agendamiento, el fin supera la vinculación del doctor, "
                    + "hay citas agendadas en el rango o se cruza con otro descanso")
    })
    public ResponseEntity<TimeOffResponse> create(
            @RequestBody @Validated @NotNull(message = "El descanso a registrar debe ser proporcionado")
            CreateTimeOffRequest request
    ) {
        LocalDate today = LocalDate.now();
        DoctorTimeOff created = timeOffService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TimeOffResponse.fromEntity(created, today));
    }

    /** Historial completo de descansos del doctor, del más reciente al más antiguo. */
    @GetMapping("/{doctorId}")
    @Operation(summary = "Consultar los descansos de un doctor",
            description = "Devuelve el historial completo de descansos del doctor, del más reciente al más antiguo. "
                    + "Cada descanso trae su estado: PROGRAMADO (aún no empieza), EN_CURSO o FINALIZADO. "
                    + "La lista puede estar vacía si el doctor nunca ha tenido descansos.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Historial de descansos obtenido correctamente"),
            @ApiResponse(responseCode = "400", description = "El identificador del doctor no es válido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar descansos"),
            @ApiResponse(responseCode = "404", description = "No existe un doctor con el identificador proporcionado")
    })
    public ResponseEntity<List<TimeOffResponse>> getByDoctor(
            @Parameter(description = "Identificador único (UUID) del doctor")
            @PathVariable @NotNull(message = "El id del doctor es requerido") UUID doctorId
    ) {
        LocalDate today = LocalDate.now();
        return ResponseEntity.ok(timeOffService.getByDoctor(doctorId).stream()
                .map(t -> TimeOffResponse.fromEntity(t, today))
                .toList());
    }

    /** Elimina un descanso futuro o recorta uno en curso (libera hoy en adelante). */
    @DeleteMapping("/{timeOffId}")
    @Operation(summary = "Eliminar o recortar un descanso",
            description = "Si el descanso aún no empieza (empieza hoy o después) se elimina. Si está en curso se recorta: "
                    + "su fecha de fin pasa a ser ayer, de modo que hoy y los días siguientes quedan libres para agendar y los "
                    + "días ya descansados se conservan como historial. Si ya terminó no se puede modificar. "
                    + "Queda registrado en la auditoría como eliminado o recortado.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Descanso eliminado o recortado correctamente"),
            @ApiResponse(responseCode = "400", description = "El identificador del descanso no es válido"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para eliminar descansos"),
            @ApiResponse(responseCode = "404", description = "No existe un descanso con el identificador proporcionado"),
            @ApiResponse(responseCode = "409", description = "El descanso ya terminó y se conserva como historial")
    })
    public ResponseEntity<Void> delete(
            @Parameter(description = "Identificador único (UUID) del descanso")
            @PathVariable @NotNull(message = "El id del descanso es requerido") UUID timeOffId
    ) {
        timeOffService.delete(timeOffId);
        return ResponseEntity.noContent().build();
    }
}
