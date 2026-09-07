package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record WorkingDateSlots(
        LocalDate date,
        List<LocalTime> slots
) {}