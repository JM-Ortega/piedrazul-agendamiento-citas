package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;

import java.time.LocalTime;
import java.util.UUID;

public record ScheduleResponse(
        UUID idSchedule,
        UUID idDoctor,
        LocalTime startTime,
        LocalTime endTime,
        Workday workday
) {
    // Un método estático para convertir la entidad en DTO fácilmente
    public static ScheduleResponse fromEntity(Schedule schedule) {
        return new ScheduleResponse(
                schedule.getIdSchedule(),
                schedule.getDoctor().getIdDoctor(),
                schedule.getStartTime(),
                schedule.getEndTime(),
                schedule.getWorkday()
        );
    }
}

