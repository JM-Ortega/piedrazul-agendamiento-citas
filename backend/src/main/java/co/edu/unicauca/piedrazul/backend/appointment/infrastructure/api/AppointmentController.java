package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.AutonomousPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentPatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.DoctorConfigInconsistentException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.AppointmentRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.ClinicalHistoryDescription;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.ListAppointmentFiltersRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.RegisterUnscheduledAttentionRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentResponse;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.PageResponse;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.CitaDtoMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Citas", description = "Operaciones de agendamiento, gestión y consulta de citas médicas")
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {
    private final ListAppointmentsUseCase listAppointmentsUseCase;
    private final CitaDtoMapper citaDtoMapper;
    private final IsNewPatientUseCase isNewPatientUseCase;
    private final UpdateAppointmentStatusUseCase updateAppointmentStatusUseCase;
    private final CancelAppointmentUseCase cancelAppointmentUseCase;
    private final GetAppointmentStatesUseCase getAppointmentStatesUseCase;
    private final UpdateAutonomousSchedulingUseCase updateAutonomousSchedulingUseCase;
    private final GetAutonomousSchedulingContidionUseCase getAutonomousSchedulingContidionUseCase;
    private final RegisterUnscheduledAttentionUseCase registerUnscheduledAttentionUseCase;
    private final GetDoctorDailyAgendaUseCase getDoctorDailyAgendaUseCase;
    private final CountScheduledAppointmentsUseCase countScheduledAppointmentsUseCase;

    private final AppointmentSchedulingService appointmentSchedulingService;
    private final ManualPatientResolutionStrategy manualPatientResolutionStrategy;
    private final AutonomousPatientResolutionStrategy autonomousPatientResolutionStrategy;
    private final PatientConsultPort patientConsultPort;
    private final DoctorConfigConsultPort doctorConfigConsultPort;

    public AppointmentController(
            ListAppointmentsUseCase listAppointmentsUseCase,
            CitaDtoMapper citaDtoMapper,
            IsNewPatientUseCase isNewPatientUseCase,
            UpdateAppointmentStatusUseCase updateAppointmentStatusUseCase,
            CancelAppointmentUseCase cancelAppointmentUseCase,
            GetAppointmentStatesUseCase getAppointmentStatesUseCase,
            UpdateAutonomousSchedulingUseCase updateAutonomousSchedulingUseCase,
            GetAutonomousSchedulingContidionUseCase getAutonomousSchedulingContidionUseCase,
            RegisterUnscheduledAttentionUseCase registerUnscheduledAttentionUseCase, GetDoctorDailyAgendaUseCase getDoctorDailyAgendaUseCase, CountScheduledAppointmentsUseCase countScheduledAppointmentsUseCase,
            AppointmentSchedulingService appointmentSchedulingService,
            ManualPatientResolutionStrategy manualPatientResolutionStrategy,
            AutonomousPatientResolutionStrategy autonomousPatientResolutionStrategy,
            PatientConsultPort patientConsultPort,
            DoctorConfigConsultPort doctorConfigConsultPort) {
        this.listAppointmentsUseCase = listAppointmentsUseCase;
        this.citaDtoMapper = citaDtoMapper;
        this.isNewPatientUseCase = isNewPatientUseCase;
        this.updateAppointmentStatusUseCase = updateAppointmentStatusUseCase;
        this.cancelAppointmentUseCase = cancelAppointmentUseCase;
        this.getAppointmentStatesUseCase = getAppointmentStatesUseCase;
        this.updateAutonomousSchedulingUseCase = updateAutonomousSchedulingUseCase;
        this.getAutonomousSchedulingContidionUseCase = getAutonomousSchedulingContidionUseCase;
        this.registerUnscheduledAttentionUseCase = registerUnscheduledAttentionUseCase;
        this.getDoctorDailyAgendaUseCase = getDoctorDailyAgendaUseCase;
        this.countScheduledAppointmentsUseCase = countScheduledAppointmentsUseCase;
        this.appointmentSchedulingService = appointmentSchedulingService;
        this.manualPatientResolutionStrategy = manualPatientResolutionStrategy;
        this.autonomousPatientResolutionStrategy = autonomousPatientResolutionStrategy;
        this.patientConsultPort = patientConsultPort;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
    }

    @PutMapping("/config/autonomous-scheduling")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Configurar agendamiento autónomo", description = "Permite activar o desactivar globalmente la condición para el agendamiento autónomo en el sistema.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Configuración actualizada correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para modificar la configuración")
    })
    public ResponseEntity<Void> setAutonomousSchedulingEnabled(
            @Parameter(description = "Valor booleano para habilitar (true) o deshabilitar (false) el agendamiento")
            @RequestParam boolean enabled) {
        updateAutonomousSchedulingUseCase.setEnabledAutonomous(enabled);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/config/autonomous-scheduling")
    @PreAuthorize("hasAnyRole('ADMIN', 'SCHEDULER', 'PATIENT', 'DOCTOR')")
    @Operation(summary = "Consultar estado del agendamiento autónomo", description = "Devuelve un booleano indicando si el agendamiento autónomo está activo actualmente en el sistema.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado obtenido correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar la configuración")
    })
    public ResponseEntity<Boolean> getAutonomousSchedulingStatus() {
        boolean enabled = getAutonomousSchedulingContidionUseCase.isAutonomousSchedulingEnabled();
        return ResponseEntity.ok(enabled);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    @Operation(summary = "Listar y filtrar citas",
            description = "Lista citas del sistema filtrando por doctor, paciente, fecha o estado." +
                    " Ajusta los permisos de filtrado dinámicamente según el rol (el paciente solo ve las suyas," +
                    " el doctor solo las suyas, el scheduler ve todas).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Citas obtenidas correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar citas"),
            @ApiResponse(responseCode = "404", description = "Doctor o Paciente asociado al token no encontrado")
    })
    public ResponseEntity<PageResponse<AppointmentResponse>> list(
            @ModelAttribute ListAppointmentFiltersRequest request,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {

        UUID userId = UUID.fromString(jwt.getSubject());

        if (hasRole(authentication, "SCHEDULER")) {
            // Sin restricción
        } else if (hasRole(authentication, "PATIENT")) {
            UUID idPatient = patientConsultPort.findByUserId(userId)
                    .map(PatientSnapshot::idPatient)
                    .orElseThrow(() -> new AppointmentPatientNotFoundException(
                            "Paciente no encontrado para el userId: " + userId));
            request.setIdPatient(idPatient);
        } else if (hasRole(authentication, "DOCTOR")) {
            UUID idDoctor = doctorConfigConsultPort.findByUserId(userId)
                    .orElseThrow(() -> new DoctorConfigInconsistentException(
                            "Doctor no encontrado para el userId: " + userId));
            request.setIdDoctor(idDoctor);
        }

        PageQuery pageQuery = request.toPageQuery();
        PagedResult<Appointment> appointmentPage = listAppointmentsUseCase.listBy(
                request.getIdDoctor(), request.getIdPatient(), request.getDate(), request.getState(), pageQuery);

        List<AppointmentResponse> content = citaDtoMapper.toResponseList(appointmentPage.content());
        return ResponseEntity.ok(PageResponse.from(appointmentPage, content));
    }

    @GetMapping("/doctor-daily-agenda")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Obtener agenda diaria del doctor",
            description = "Lista de manera paginada las citas del día para el doctor autenticado, organizadas por prioridad de estado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agenda obtenida correctamente"),
            @ApiResponse(responseCode = "400", description = "Parámetros de búsqueda inválidos"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar esta agenda"),
            @ApiResponse(responseCode = "404", description = "Doctor no encontrado")
    })
    public ResponseEntity<PageResponse<AppointmentResponse>> getDoctorDailyAgenda(
            @Parameter(description = "Fecha de la agenda a consultar en formato yyyy-MM-dd")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Número de página (inicia en 0)")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Tamaño de la página")
            @RequestParam(defaultValue = "5") int size,
            @AuthenticationPrincipal Jwt jwt) {

        UUID idDoctor = doctorConfigConsultPort.findByUserId(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new DoctorConfigInconsistentException("Doctor no encontrado"));

        PageQuery pageQuery = new PageQuery(Math.max(page, 0), Math.min(Math.max(size, 1), 100), "date", true);

        PagedResult<Appointment> result = getDoctorDailyAgendaUseCase.execute(idDoctor, date, pageQuery);
        List<AppointmentResponse> content = citaDtoMapper.toResponseList(result.content());

        return ResponseEntity.ok(PageResponse.from(result, content));
    }

    @GetMapping({ "/{patientId}/is-new-patient" })
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    @Operation(summary = "Verificar si es paciente nuevo", description = "Consulta en el sistema si el paciente especificado es nuevo o ya tiene historial.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consulta realizada con éxito"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para realizar esta consulta"),
            @ApiResponse(responseCode = "404", description = "Paciente no encontrado")
    })
    public ResponseEntity<Boolean> isNewPatient(
            @Parameter(description = "Identificador único (UUID) del paciente")
            @PathVariable UUID patientId) {
        return ResponseEntity.ok(isNewPatientUseCase.isNewPatient(patientId));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    @Operation(summary = "Agendar una cita",
            description = "Programa una cita médica. Maneja agendamiento manual (SCHEDULER/DOCTOR) y autónomo (PATIENT)," +
                    " aplicando las estrategias de resolución de pacientes correspondientes.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Cita agendada correctamente"),
            @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos o faltantes"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para agendar citas"),
            @ApiResponse(responseCode = "404", description = "Paciente o doctor no encontrado"),
            @ApiResponse(responseCode = "409", description = "Conflicto con la disponibilidad de horario")
    })
    public ResponseEntity<Void> scheduleAppointment(
            @Parameter(description = "Datos para el agendamiento de la cita")
            @RequestBody @Valid AppointmentRequest request,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {

        request.validate();
        UUID performedBy = resolvePerformedBy(jwt);

        if (request.getSchedulingOrigin() == SchedulingOrigin.AUTONOMO && hasRole(authentication, "PATIENT")) {
            UUID idPatient = patientConsultPort.findByUserId(performedBy)
                    .map(PatientSnapshot::idPatient)
                    .orElseThrow(() -> new AppointmentPatientNotFoundException(
                            "Paciente no encontrado para el userId: " + performedBy));
            request.setPatientId(idPatient);
        }

        switch (request.getSchedulingOrigin()) {
            case MANUAL -> appointmentSchedulingService.scheduleManual(
                    PatientSchedulingContext.manual(
                            request.getDocumentType(),
                            request.getDocumentNumber(),
                            request.getFirstName(),
                            request.getLastName(),
                            request.getPhone(),
                            request.getGender(),
                            request.getBirthDate(),
                            request.getEmail(),
                            request.getGuardianPhone()),
                    request.getDoctorId(),
                    request.getSpecialty(),
                    request.getDate(),
                    new AppointmentTime(request.getStartTime()),
                    performedBy,
                    manualPatientResolutionStrategy);

            case AUTONOMO -> appointmentSchedulingService.scheduleAutonomous(
                    PatientSchedulingContext.autonomous(request.getPatientId()),
                    request.getDoctorId(),
                    request.getSpecialty(),
                    request.getDate(),
                    new AppointmentTime(request.getStartTime()),
                    performedBy,
                    autonomousPatientResolutionStrategy);
        }

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/unscheduled")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Registrar atención no agendada",
            description = "Registra una cita de atención inmediata no agendada previamente," +
                    " con la opción de asociarla a un control medico (Medical Check up).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Atención registrada correctamente"),
            @ApiResponse(responseCode = "400", description = "Datos de entrada inválidos"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para registrar la atención"),
            @ApiResponse(responseCode = "404", description = "Doctor no encontrado")
    })
    public ResponseEntity<Void> registerUnscheduledAttention(
            @Parameter(description = "Datos de la atención no agendada")
            @RequestBody @Valid RegisterUnscheduledAttentionRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        UUID idDoctor = doctorConfigConsultPort.findByUserId(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new DoctorConfigInconsistentException("Doctor no encontrado"));

        PatientSchedulingContext patientContext = PatientSchedulingContext.manual(
                request.getDocumentType(), request.getDocumentNumber(), request.getFirstName(),
                request.getLastName(), request.getPhone(), request.getGender(),
                request.getBirthDate(), request.getEmail(), request.getGuardianPhone());

        registerUnscheduledAttentionUseCase.register(idDoctor, patientContext, request.getSpecialty(),
                request.getMedicalCheckup());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/{appointmentId}/mark-as-attended")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Marcar cita como atendida", description = "Actualiza el estado de la cita a 'ATENDIDA' e inicia el proceso para asociarle un control medico.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado de cita actualizado correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para modificar la cita"),
            @ApiResponse(responseCode = "404", description = "Cita no encontrada")
    })
    public ResponseEntity<Void> markAppointmentAsAttended(
            @Parameter(description = "Identificador único (UUID) de la cita")
            @PathVariable UUID appointmentId,
            @Parameter(description = "Descripción opcional para el registro de un control medico")
            @RequestBody(required = false) ClinicalHistoryDescription request) {
        String description = (request != null) ? request.description() : null;
        updateAppointmentStatusUseCase.markAsAttended(appointmentId, description);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{appointmentId}/mark-as-unassisted")
    @PreAuthorize("hasRole('DOCTOR')")
    @Operation(summary = "Marcar cita como no asistida", description = "Actualiza el estado de la cita indicando que el paciente no se presentó (NO ASISTIDA).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estado de cita actualizado correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para modificar la cita"),
            @ApiResponse(responseCode = "404", description = "Cita no encontrada")
    })
    public ResponseEntity<Void> markAppointmentAsUnassisted(
            @Parameter(description = "Identificador único (UUID) de la cita")
            @PathVariable UUID appointmentId) {
        updateAppointmentStatusUseCase.markAsUnassisted(appointmentId);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/{appointmentId}/cancel")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT')")
    @Operation(summary = "Cancelar una cita", description = "Cancela una cita previamente agendada. Si es un paciente, validará que la cita le pertenezca.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cita cancelada correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para cancelar la cita"),
            @ApiResponse(responseCode = "404", description = "Cita o paciente no encontrado")
    })
    public ResponseEntity<Void> cancelAppointment(
            @Parameter(description = "Identificador único (UUID) de la cita a cancelar")
            @PathVariable UUID appointmentId,
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {

        UUID patientId = null;

        if (hasRole(authentication, "PATIENT")) {
            UUID userId = UUID.fromString(jwt.getSubject());
            patientId = patientConsultPort.findByUserId(userId)
                    .map(PatientSnapshot::idPatient)
                    .orElseThrow(() -> new AppointmentPatientNotFoundException(
                            "Paciente no encontrado para el userId: " + userId));
        }

        cancelAppointmentUseCase.cancel(appointmentId, patientId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/list-all-states")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR')")
    @Operation(summary = "Listar estados de citas", description = "Devuelve una lista con todos los estados posibles que pueden tomar las citas en el sistema.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Estados obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar los estados")
    })
    public ResponseEntity<List<AppointmentState>> listAppointmentStates() {
        List<AppointmentState> states = getAppointmentStatesUseCase.getAppointmentStates();
        return ResponseEntity.ok(states);
    }

    @GetMapping("/countScheduledAppointments")
    @PreAuthorize("hasAnyRole('SCHEDULER')")
    @Operation(summary = "Contar citas agendadas",
            description = "Obtiene la cantidad exacta de citas que se encuentran con el estado de 'AGENDADA' para un día específico.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Conteo obtenido correctamente"),
            @ApiResponse(responseCode = "400", description = "Fecha proporcionada inválida"),
            @ApiResponse(responseCode = "401", description = "No autenticado"),
            @ApiResponse(responseCode = "403", description = "No tiene permisos para consultar esta información")
    })
    public ResponseEntity<Long> countScheduledAppointments(
            @Parameter(description = "Fecha sobre la cual contar las citas, en formato yyyy-MM-dd")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(countScheduledAppointmentsUseCase.execute(date));
    }

    // Helper methods
    private UUID resolvePerformedBy(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    private boolean hasRole(Authentication authentication, String role) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
}