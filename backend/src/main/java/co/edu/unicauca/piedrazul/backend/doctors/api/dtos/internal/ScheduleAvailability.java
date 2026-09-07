package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.shared.enums.Workday;

import java.time.LocalTime;

public record ScheduleAvailability(
        LocalTime startTime,
        LocalTime endTime,
        Workday workday
) {
    public static ScheduleAvailability fromEntity(Schedule schedule) {
        return new ScheduleAvailability(
                schedule.getStartTime(),
                schedule.getEndTime(),
                schedule.getWorkday()
        );
    }
}
