package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;

import java.time.LocalDate;
import java.util.UUID;

public interface ScheduleAutonomousAppointmentUseCase {
    // Paciente agenda de forma autónoma por la web
    Appointment scheduleAutonomous(UUID idPatient,
                                   UUID idDoctor,
                                   Specialty specialty,
                                   LocalDate date,
                                   AppointmentTime startTime,
                                   String performedBy);
}
