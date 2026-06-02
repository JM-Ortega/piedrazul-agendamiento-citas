package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal;

import java.time.LocalTime;

public record SchedulerAppointmentSummary(
        String doctorName,
        String patientName,
        LocalTime startTime
) {}
