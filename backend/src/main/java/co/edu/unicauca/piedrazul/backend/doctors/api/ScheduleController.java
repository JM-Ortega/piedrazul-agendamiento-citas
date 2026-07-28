package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.ScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.AvailableIntervalsResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.ScheduleResponse;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.application.ScheduleService;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/doctor/schedules")
@PreAuthorize("hasRole('ADMIN')")
public class ScheduleController {
    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /**
     * Actualiza el horario de atención de un doctor específico.
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code ADMIN}.
     * </p>
     *
     * @param doctorId Identificador único (UUID) del doctor cuyo horario se actualizará. No debe ser nulo.
     * @param request  Objeto {@link ScheduleRequest} que contiene la nueva configuración del horario. Debe ser válido.
     * @return Un {@link ResponseEntity} con estado {@code 204 No Content} si la actualización fue exitosa.
     * @throws DoctorNotFoundException si no existe ningún doctor asociado al {@code doctorId} proporcionado.
     */
    @PutMapping("/{doctorId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateSchedule(
            @PathVariable @NotNull(message = "El id del doctor es requerido")
            UUID doctorId,
            @RequestBody @Validated @NotNull(message = "El  horario a actualizar debe ser proporcinoado")
            ScheduleRequest request
    ) {

        scheduleService.updateSchedule(doctorId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Eliminar el horario de un doctor para un día específico
     * @param doctorId
     * @param workday
     * @return
     */
    @DeleteMapping("/{doctorId}/{workday}")
    public ResponseEntity<?> deleteSchedule(
            @PathVariable @NotNull(message = "El id del doctor es requerido")
            UUID doctorId,
            @PathVariable @NotNull(message = "El dia del horario a eliminar es requerido")
            Workday workday
    ) {

        scheduleService.deleteScheduleByWorkday(doctorId, workday);
        return ResponseEntity.noContent().build();
    }

    /**
     * Obtener todos los horarios de un doctor
     * @param doctorId ID del doctor
     * @return Lista de horarios del doctor
     */
    @GetMapping("/{doctorId}")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<?> getSchedulesByDoctor(
            @PathVariable @NotNull(message = "El id del doctor es requerido")
            UUID doctorId) {

        List<ScheduleResponse> responses = scheduleService.getSchedulesByDoctor(doctorId).stream()
                .map(ScheduleResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * Obtener los intervalos disponibles para agendar citas en un día específico
     * @param doctorId ID del doctor
     * @param workday Día de la semana
     * @return Lista de horarios disponibles
     */
    @GetMapping("/{doctorId}/available-intervals/{workday}")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<?> getAvailableIntervals(
            @PathVariable @NotNull(message = "El id del doctor es requerido")
            UUID doctorId,
            @PathVariable @NotNull(message = "El dia es requerido")
            Workday workday
    ) {
        List<LocalTime> availableIntervals = scheduleService.getAvailableIntervalsByWorkday(doctorId, workday);

        AvailableIntervalsResponse response = new AvailableIntervalsResponse(
                workday.toString(),
                availableIntervals
        );

        return ResponseEntity.ok(response);
    }

}
