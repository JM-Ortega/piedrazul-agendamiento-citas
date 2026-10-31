package co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.proyections;

import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import java.util.UUID;

public record DoctorSpecialtyProjection(
        UUID personId,
        SpecialtyCode specialty
) {}