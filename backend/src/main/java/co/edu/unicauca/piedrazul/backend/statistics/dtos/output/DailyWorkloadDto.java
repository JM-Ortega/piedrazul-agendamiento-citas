package co.edu.unicauca.piedrazul.backend.statistics.dtos.output;

import java.util.List;

public record DailyWorkloadDto(int dayTotal, int averagePerDoctor, List<DoctorWorkloadDto> doctors) {}
