package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record AvailableDateSlots(
        LocalDate date,
        List<LocalTime> availableSlots
) {}