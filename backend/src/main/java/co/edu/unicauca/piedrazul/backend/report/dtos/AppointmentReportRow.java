package co.edu.unicauca.piedrazul.backend.report.dtos;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AppointmentReportRow(
        UUID idAppointment,
        UUID idPatient,
        String patientFullName,
        String document,
        String phoneNumber,
        LocalDate date,
        LocalTime startTime,
        String specialty,
        String state
){}
