package co.edu.unicauca.piedrazul.backend.doctors.controller.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.model.models.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.model.models.Workday;

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

