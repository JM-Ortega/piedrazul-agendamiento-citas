package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.NoDoctorsAvailableException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.*;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.AppointmentRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentResponse;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.SpecialtyDoctorResponse;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.CitaDtoMapper;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {
    private final GetAvailableSlotsUseCase getAvailableSlotsUseCase;
    private final ScheduleManualAppointmentUseCase scheduleManualAppointmentUseCase;
    private final ScheduleAutonomousAppointmentUseCase scheduleAutonomousAppointmentUseCase;
    private final ListAppointmentsUseCase listAppointmentsUseCase;
    private final GetAvailableDoctorsBySpecialtyUseCase getAvailableDoctorsBySpecialtyUseCase;
    private final CitaDtoMapper citaDtoMapper;

    public AppointmentController(
            GetAvailableSlotsUseCase getAvailableSlotsUseCase,
            ScheduleManualAppointmentUseCase scheduleManualAppointmentUseCase,
            ScheduleAutonomousAppointmentUseCase scheduleAutonomousAppointmentUseCase,
            ListAppointmentsUseCase listAppointmentsUseCase,
            GetAvailableDoctorsBySpecialtyUseCase getAvailableDoctorsBySpecialtyUseCase,
            CitaDtoMapper citaDtoMapper) {
        this.getAvailableSlotsUseCase = getAvailableSlotsUseCase;
        this.scheduleManualAppointmentUseCase = scheduleManualAppointmentUseCase;
        this.scheduleAutonomousAppointmentUseCase = scheduleAutonomousAppointmentUseCase;
        this.listAppointmentsUseCase = listAppointmentsUseCase;
        this.getAvailableDoctorsBySpecialtyUseCase = getAvailableDoctorsBySpecialtyUseCase;
        this.citaDtoMapper = citaDtoMapper;
    }

    // Franjas disponibles según el médico y la fecha
    @GetMapping("/available-slots")
    public ResponseEntity<List<AppointmentTime>> getAvailableSlots(
            @RequestParam UUID doctorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        List<AppointmentTime> slots = getAvailableSlotsUseCase
                .getAvailableSlots(doctorId, date);

        return ResponseEntity.ok(slots);
    }

    // Un unico método para listar por idDoctor, por fecha, por ambos o para pasar todas las citas que hay
    @GetMapping
    public ResponseEntity<List<AppointmentResponse>> list(
            @RequestParam(required = false) UUID idDoctor,
            @RequestParam(required = false) LocalDate date) {

        // Mapper para pasar de Domain a DTO
        return ResponseEntity.ok(listAppointmentsUseCase.listBy(idDoctor, date).stream().map(citaDtoMapper::toResponse).toList());
    }

    // Crear cita
    @PostMapping
    public ResponseEntity<Void> scheduleAppointment(
            @RequestBody @Valid AppointmentRequest request) {

        Appointment appointment = switch (request.getSchedulingOrigin()) {

            // Agendador manual, el paciente no tiene cuenta
            case MANUAL -> scheduleManualAppointmentUseCase.scheduleManual(
                    citaDtoMapper.toPatientInfo(request),
                    request.getDoctorId(),
                    request.getSpecialty(),
                    request.getDate(),
                    new AppointmentTime(request.getStartTime())
            );

            // Paciente agenda por la web, ya tiene cuenta en el sistema
            case AUTONOMO -> scheduleAutonomousAppointmentUseCase.scheduleAutonomous(
                    request.getPatientId(),
                    request.getDoctorId(),
                    request.getSpecialty(),
                    request.getDate(),
                    new AppointmentTime(request.getStartTime())
            );
        };

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // Listar un médico por defecto para cada especialidad
    @GetMapping("/specialties-with-doctor")
    public ResponseEntity<List<SpecialtyDoctorResponse>> getSpecialtiesWithDoctor() {
        try {
            List<SpecialtyDoctorResponse> response =
                    getAvailableDoctorsBySpecialtyUseCase.getSpecialtiesWithDoctor();
            return ResponseEntity.ok(response);
        } catch (NoDoctorsAvailableException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }
}
