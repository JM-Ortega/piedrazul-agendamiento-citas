package co.edu.unicauca.piedrazul.backend.appointment;

import java.time.LocalTime;

public record SchedulerAppointmentSummary(
        String doctorName,
        String patientName,
        LocalTime startTime
) {}
