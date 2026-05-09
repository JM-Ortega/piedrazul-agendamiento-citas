package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;

import java.time.LocalDate;
import java.util.UUID;

public interface ScheduleManualAppointmentUseCase {
    // Agendador crea la cita manualmente
    Appointment scheduleManual(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            Gender gender,
            LocalDate birthDate,
            String email,
            String guardianPhone,
            UUID idDoctor,
            Specialty specialty,
            LocalDate date,
            AppointmentTime startTime,
            String performedBy
    );
}
