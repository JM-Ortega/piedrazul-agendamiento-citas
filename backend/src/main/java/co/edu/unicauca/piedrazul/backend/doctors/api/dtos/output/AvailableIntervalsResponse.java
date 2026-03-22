package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import java.time.LocalTime;

public record AvailableIntervalsResponse(
        String workday,
        java.util.List<LocalTime> availableSlots
) {}

