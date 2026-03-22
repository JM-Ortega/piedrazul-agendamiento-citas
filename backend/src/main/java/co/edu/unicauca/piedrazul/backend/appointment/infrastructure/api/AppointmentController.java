package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleAutonomousAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleManualAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.AppointmentRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentResponse;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.CitaDtoMapper;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final CitaDtoMapper citaDtoMapper;

    public AppointmentController(
            GetAvailableSlotsUseCase getAvailableSlotsUseCase,
            ScheduleManualAppointmentUseCase scheduleManualAppointmentUseCase,
            ScheduleAutonomousAppointmentUseCase scheduleAutonomousAppointmentUseCase,
            ListAppointmentsUseCase listAppointmentsUseCase,
            CitaDtoMapper citaDtoMapper) {
        this.getAvailableSlotsUseCase = getAvailableSlotsUseCase;
        this.scheduleManualAppointmentUseCase = scheduleManualAppointmentUseCase;
        this.scheduleAutonomousAppointmentUseCase = scheduleAutonomousAppointmentUseCase;
        this.listAppointmentsUseCase = listAppointmentsUseCase;
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

    // Crear cita
    @PostMapping
    public ResponseEntity<AppointmentResponse> scheduleAppointment(
            @RequestBody @Valid AppointmentRequest request) {

        Appointment appointment = switch (request.getSchedulingOrigin()) {

            // Agendador manual, el paciente no tiene cuenta
            case WHATSAPP -> scheduleManualAppointmentUseCase.scheduleManual(
                    citaDtoMapper.toPatientInfo(request),
                    request.getDoctorId(),
                    request.getSpecialty(),
                    request.getDate(),
                    new AppointmentTime(request.getStartTime())
            );

            // Paciente agenda por la web, ya tiene cuenta en el sistema
            case WEB -> scheduleAutonomousAppointmentUseCase.scheduleAutonomous(
                    request.getPatientId(),
                    request.getDoctorId(),
                    request.getSpecialty(),
                    request.getDate(),
                    new AppointmentTime(request.getStartTime())
            );
        };

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(citaDtoMapper.toResponse(appointment));
    }
}
