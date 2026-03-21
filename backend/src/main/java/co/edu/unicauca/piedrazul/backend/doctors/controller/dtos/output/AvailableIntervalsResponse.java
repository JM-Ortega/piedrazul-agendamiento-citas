package co.edu.unicauca.piedrazul.backend.doctors.controller.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.model.models.Workday;

import java.time.LocalTime;

public record AvailableIntervalsResponse(
        String workday,
        java.util.List<LocalTime> availableSlots
) {}

