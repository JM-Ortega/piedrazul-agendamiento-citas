package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal;

import java.util.List;

public record WorkingSchedule(
        List<WorkingDateSlots> datesAndSlots,
        int appointmentInterval
) {}
