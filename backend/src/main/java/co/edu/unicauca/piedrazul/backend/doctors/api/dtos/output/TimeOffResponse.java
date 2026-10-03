package co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output;

import co.edu.unicauca.piedrazul.backend.doctors.domain.DoctorTimeOff;
import co.edu.unicauca.piedrazul.backend.doctors.domain.TimeOffStatus;

import java.time.LocalDate;
import java.util.UUID;

public record TimeOffResponse(
        UUID id,
        UUID doctorId,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        TimeOffStatus status
) {
    public static TimeOffResponse fromEntity(DoctorTimeOff timeOff, LocalDate today) {
        return new TimeOffResponse(
                timeOff.getId(),
                timeOff.getDoctorId(),
                timeOff.getStartDate(),
                timeOff.getEndDate(),
                timeOff.getReason(),
                timeOff.statusOn(today)
        );
    }
}
