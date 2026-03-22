package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;

import java.time.LocalDate;
import java.util.UUID;

public interface ScheduleManualAppointmentUseCase {
    // Agendador crea la cita manualmente
    Appointment scheduleManual(PatientInfo patientInfo,
                               UUID idDoctor,
                               Specialty specialty,
                               LocalDate date,
                               AppointmentTime startTime);
}
