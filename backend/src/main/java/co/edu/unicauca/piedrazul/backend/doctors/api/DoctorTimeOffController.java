package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateTimeOffRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.TimeOffResponse;
import co.edu.unicauca.piedrazul.backend.doctors.application.TimeOffService;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DoctorTimeOff;
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
    public ResponseEntity<List<TimeOffResponse>> getByDoctor(
            @PathVariable @NotNull(message = "El id del doctor es requerido") UUID doctorId
    ) {
        LocalDate today = LocalDate.now();
        return ResponseEntity.ok(timeOffService.getByDoctor(doctorId).stream()
                .map(t -> TimeOffResponse.fromEntity(t, today))
                .toList());
    }

    /** Elimina un descanso futuro o recorta uno en curso (libera hoy en adelante). */
    @DeleteMapping("/{timeOffId}")
    public ResponseEntity<Void> delete(
            @PathVariable @NotNull(message = "El id del descanso es requerido") UUID timeOffId
    ) {
        timeOffService.delete(timeOffId);
        return ResponseEntity.noContent().build();
    }
}
