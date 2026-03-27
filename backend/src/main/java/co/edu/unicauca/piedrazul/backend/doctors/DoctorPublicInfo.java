package co.edu.unicauca.piedrazul.backend.doctors;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DoctorPublicInfo(
        String specialty,
        UUID id,
        String name,
        LocalDate laborEnd,
        List<Integer> workdays
) {}
