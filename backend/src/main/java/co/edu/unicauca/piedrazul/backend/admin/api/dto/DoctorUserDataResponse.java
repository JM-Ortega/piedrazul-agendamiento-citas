package co.edu.unicauca.piedrazul.backend.admin.api.dto;

public record DoctorUserDataResponse(
        String specialty,
        String startTime,
        String endTime,
        int interval
) {}