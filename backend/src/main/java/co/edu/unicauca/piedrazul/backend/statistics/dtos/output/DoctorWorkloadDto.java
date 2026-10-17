package co.edu.unicauca.piedrazul.backend.statistics.dtos.output;

import co.edu.unicauca.piedrazul.backend.statistics.dtos.WorkloadLevel;

public record DoctorWorkloadDto(String doctor, int total, WorkloadLevel level) {}
