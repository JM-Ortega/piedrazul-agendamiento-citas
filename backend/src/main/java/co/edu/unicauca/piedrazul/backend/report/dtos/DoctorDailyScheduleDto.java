package co.edu.unicauca.piedrazul.backend.report.dtos;

import java.util.List;

public record DoctorDailyScheduleDto(
        String doctorName,
        List<String> patientNames
) {
}
