package co.edu.unicauca.piedrazul.backend.appointment.dto;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AppointmentExternalData(
        UUID idAppointment,
        UUID idDoctor,
        String doctorName,
        UUID idPatient,
        String state,
        LocalDate date,
        LocalTime startTime
) {}