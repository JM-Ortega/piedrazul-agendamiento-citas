package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import java.time.LocalDate;
import java.util.UUID;

public record AppointmentExternalData(
        UUID idAppointment,
        UUID idDoctor,
        String doctorName,
        UUID idPatient,
        String state,
        LocalDate date
) { }
