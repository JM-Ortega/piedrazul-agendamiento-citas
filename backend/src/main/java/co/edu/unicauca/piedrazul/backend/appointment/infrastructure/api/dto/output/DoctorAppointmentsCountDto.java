package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output;

import java.util.List;
import java.util.UUID;

public record DoctorAppointmentsCountDto(List<UUID> doctorIds, List<MonthlyDoctorRowDto> rows) {}
