package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.AutonomousPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.*;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.AppointmentRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.ClinicalHistoryDescription;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.ListAppointmentFiltersRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentResponse;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.PageResponse;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.CitaDtoMapper;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {
    private final GetAvailableSlotsUseCase getAvailableSlotsUseCase;
    private final ListAppointmentsUseCase listAppointmentsUseCase;
    private final GetSpecialtiesWithDoctorUseCase getSpecialtiesWithDoctorUseCase;
    private final CitaDtoMapper citaDtoMapper;
    private final ListMyAppointmentsUseCase listMyAppointmentsUseCase;
    private final IsNewPatientUseCase isNewPatientUseCase;
    private final UpdateAppointmentStatusUseCase updateAppointmentStatusUseCase;
    private final CancelAppointmentUseCase cancelAppointmentUseCase;
    private final GetAppointmentStatesUseCase getAppointmentStatesUseCase;

    private final AppointmentSchedulingService appointmentSchedulingService;
    private final ManualPatientResolutionStrategy manualPatientResolutionStrategy;
    private final AutonomousPatientResolutionStrategy autonomousPatientResolutionStrategy;

    public AppointmentController(
            GetAvailableSlotsUseCase getAvailableSlotsUseCase,
            ListAppointmentsUseCase listAppointmentsUseCase,
            GetSpecialtiesWithDoctorUseCase getSpecialtiesWithDoctorUseCase,
            CitaDtoMapper citaDtoMapper,
            ListMyAppointmentsUseCase listMyAppointmentsUseCase,
            IsNewPatientUseCase isNewPatientUseCase,
            UpdateAppointmentStatusUseCase updateAppointmentStatusUseCase,
            CancelAppointmentUseCase cancelAppointmentUseCase, GetAppointmentStatesUseCase getAppointmentStatesUseCase,
            AppointmentSchedulingService appointmentSchedulingService,
            ManualPatientResolutionStrategy manualPatientResolutionStrategy,
            AutonomousPatientResolutionStrategy autonomousPatientResolutionStrategy) {
        this.getAvailableSlotsUseCase = getAvailableSlotsUseCase;
        this.listAppointmentsUseCase = listAppointmentsUseCase;
        this.getSpecialtiesWithDoctorUseCase = getSpecialtiesWithDoctorUseCase;
        this.citaDtoMapper = citaDtoMapper;
        this.listMyAppointmentsUseCase = listMyAppointmentsUseCase;
        this.isNewPatientUseCase = isNewPatientUseCase;
        this.updateAppointmentStatusUseCase = updateAppointmentStatusUseCase;
        this.cancelAppointmentUseCase = cancelAppointmentUseCase;
        this.getAppointmentStatesUseCase = getAppointmentStatesUseCase;
        this.appointmentSchedulingService = appointmentSchedulingService;
        this.manualPatientResolutionStrategy = manualPatientResolutionStrategy;
        this.autonomousPatientResolutionStrategy = autonomousPatientResolutionStrategy;
    }

    // Franjas disponibles según el médico y la fecha
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    @GetMapping("/available-slots")
    public ResponseEntity<List<AppointmentTime>> getAvailableSlots(
            @RequestParam UUID doctorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        List<AppointmentTime> slots = getAvailableSlotsUseCase
                .getAvailableSlots(doctorId, date);

        return ResponseEntity.ok(slots);
    }

    // Un unico método para listar por idDoctor, idPatient, fecha, estado o combinaciones.
    @GetMapping
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<PageResponse<AppointmentResponse>> list(
            @ModelAttribute ListAppointmentFiltersRequest request) {

        PageQuery pageQuery = request.toPageQuery();

        PagedResult<Appointment> appointmentPage = listAppointmentsUseCase.listBy(
                request.getIdDoctor(),
                request.getIdPatient(),
                request.getDate(),
                request.getState(),
                pageQuery
        );

        List<AppointmentResponse> content = citaDtoMapper.toResponseList(appointmentPage.content());

        PageResponse<AppointmentResponse> response = PageResponse.from(appointmentPage, content);
        return ResponseEntity.ok(response);
    }


    @GetMapping("/me")
    @PreAuthorize("hasRole('PATIENT')")
    public ResponseEntity<List<AppointmentResponse>> listMyAppointments(
            @AuthenticationPrincipal Jwt jwt) {

        UUID userId = UUID.fromString(jwt.getSubject());

        return ResponseEntity.ok(
                listMyAppointmentsUseCase.execute(userId)
                        .stream()
                        .map(citaDtoMapper::toResponse)
                        .toList()
        );
    }

    // Sirve para saber si un paciente es nuevo
    @GetMapping({"/{patientId}/is-new-patient"})
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<Boolean> isNewPatient(@PathVariable UUID patientId) {
        return ResponseEntity.ok(isNewPatientUseCase.isNewPatient(patientId));
    }

    // Crear cita
    @PostMapping
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<Void> scheduleAppointment(
            @RequestBody @Valid AppointmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        request.validate();
        UUID performedBy = resolvePerformedBy(jwt);

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
                            request.getGuardianPhone()
                    ),
                    request.getDoctorId(),
                    request.getSpecialty(),
                    request.getDate(),
                    new AppointmentTime(request.getStartTime()),
                    performedBy,
                    manualPatientResolutionStrategy
            );

            case AUTONOMO -> appointmentSchedulingService.scheduleAutonomous(
                    PatientSchedulingContext.autonomous(request.getPatientId()),
                    request.getDoctorId(),
                    request.getSpecialty(),
                    request.getDate(),
                    new AppointmentTime(request.getStartTime()),
                    performedBy,
                    autonomousPatientResolutionStrategy
            );
        }

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // Listar un médico por defecto para cada especialidad
    @GetMapping("/specialties-with-doctor")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public ResponseEntity<List<DoctorResponse>> getSpecialtiesWithDoctor(@RequestParam(required = false) UUID patientId) {
        return ResponseEntity.ok(getSpecialtiesWithDoctorUseCase.getSpecialtiesWithDoctor(patientId));
    }

    // Actualizar el estado de una cita a atendida y crear su HC asociada
    @PutMapping("/{appointmentId}/mark-as-attended")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<Void> markAppointmentAsAttended(@PathVariable UUID appointmentId
        , @RequestBody(required = false) ClinicalHistoryDescription request) {
        String description = (request != null) ? request.description() : null;
        updateAppointmentStatusUseCase.markAsAttended(appointmentId, description);
        return ResponseEntity.ok().build();
    }

    // Actualizar el estado de una cita a no asistida
    @PutMapping("/{appointmentId}/mark-as-unassisted")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<Void> markAppointmentAsUnassisted(@PathVariable UUID appointmentId) {
        updateAppointmentStatusUseCase.markAsUnassisted(appointmentId);
        return ResponseEntity.ok().build();
    }

    //Cancelar una cita
    @PutMapping("/{appointmentId}/cancel")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT')")
    public ResponseEntity<Void> cancelAppointment(@PathVariable UUID appointmentId) {
        cancelAppointmentUseCase.cancel(appointmentId);
        return ResponseEntity.noContent().build(); // 204
    }

    // Listar los estados de las citas
    @GetMapping("/list-all-states")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR')")
    public ResponseEntity<List<AppointmentState>> listAppointmentStates() {
        List<AppointmentState> states = getAppointmentStatesUseCase.getAppointmentStates();
        return ResponseEntity.ok(states);
    }

    private UUID resolvePerformedBy(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
