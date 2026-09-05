package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.ScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.AvailableIntervalsResponse;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.ScheduleResponse;
import co.edu.unicauca.piedrazul.backend.doctors.application.ScheduleService;
import co.edu.unicauca.piedrazul.backend.shared.enums.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.AuthorizationDeniedException;
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
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @PutMapping("/{doctorId}")
    public ResponseEntity<Void> updateSchedule(
            @PathVariable @NotNull(message = "El id del doctor es requerido")
            UUID doctorId,
            @RequestBody @Validated @NotNull(message = "El  horario a actualizar debe ser proporcionado")
            ScheduleRequest request
    ) {

        scheduleService.updateSchedule(doctorId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Elimina el horario de atención de un doctor para un día específico de la semana.
     * <p>
     * Si el doctor tiene un horario configurado para el día indicado, este será eliminado.
     * Si no existe un horario para ese día, la operación no realizará ningún cambio.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea el rol {@code ADMIN}.
     * </p>
     *
     * @param doctorId identificador único (UUID) del doctor cuyo horario será eliminado.
     *                 No debe ser {@code null}.
     * @param workday día de la semana correspondiente al horario que se eliminará.
     *                No debe ser {@code null}.
     * @return un {@link ResponseEntity} con estado {@code 204 No Content} si la operación
     * se completa correctamente.
     * @throws DoctorNotFoundException si no existe un doctor asociado al {@code doctorId} proporcionado.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @DeleteMapping("/{doctorId}/{workday}")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable @NotNull(message = "El id del doctor es requerido")
            UUID doctorId,
            @PathVariable @NotNull(message = "El dia del horario a eliminar es requerido")
            Workday workday
    ) {

        scheduleService.deleteScheduleByWorkday(doctorId, workday);
        return ResponseEntity.noContent().build();
    }

    /**
     * Obtiene todos los horarios de atención configurados para un doctor.
     * <p>
     * Los horarios retornados corresponden a los días de la semana en los que el doctor
     * presta atención, incluyendo la hora de inicio y la hora de finalización de cada jornada.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea alguno de los roles
     * {@code SCHEDULER}, {@code PATIENT} o {@code DOCTOR}.
     * </p>
     *
     * @param doctorId identificador único (UUID) del doctor cuyos horarios serán consultados.
     *                 No debe ser {@code null}.
     * @return un {@link ResponseEntity} con estado {@code 200 OK} que contiene una lista de
     * {@link ScheduleResponse} con los horarios configurados para el doctor.
     * La lista puede estar vacía si el doctor no tiene horarios registrados.
     * @throws DoctorNotFoundException si no existe un doctor asociado al {@code doctorId} proporcionado.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
     */
    @GetMapping("/{doctorId}")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT')")
    public ResponseEntity<?> getSchedulesByDoctor(
            @PathVariable @NotNull(message = "El id del doctor es requerido")
            UUID doctorId) {

        List<ScheduleResponse> responses = scheduleService.getSchedulesByDoctor(doctorId).stream()
                .map(ScheduleResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(responses);
    }

    /**
     * Obtiene los intervalos de tiempo disponibles para configurar una cita en un día
     * específico de la semana.
     * <p>
     * Los intervalos corresponden a las horas permitidas para establecer el inicio o el fin
     * de una jornada laboral del doctor, de acuerdo con la duración de las citas configurada.
     * </p>
     *
     * <p>
     * Requiere que el usuario autenticado posea alguno de los roles
     * {@code SCHEDULER}, {@code PATIENT} o {@code DOCTOR}.
     * </p>
     *
     * @param doctorId identificador único (UUID) del doctor cuyos intervalos serán consultados.
     *                 No debe ser {@code null}.
     * @param workday día de la semana para el cual se obtendrán los intervalos disponibles.
     *                No debe ser {@code null}.
     * @return un {@link ResponseEntity} con estado {@code 200 OK} que contiene un
     * {@link AvailableIntervalsResponse} con el día consultado y la lista de horas disponibles.
     * @throws DoctorNotFoundException si no existe un doctor asociado al {@code doctorId} proporcionado.
     * @throws AuthorizationDeniedException si el usuario autenticado no tiene permisos para acceder al recurso.
     * @throws AccessDeniedException si el acceso al recurso es denegado por Spring Security.
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
