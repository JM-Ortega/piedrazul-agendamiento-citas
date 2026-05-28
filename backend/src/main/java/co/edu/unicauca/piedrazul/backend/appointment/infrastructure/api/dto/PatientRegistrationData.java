package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;

import java.time.LocalDate;

public record PatientRegistrationData(
        DocumentType documentType,
        String documentNumber,
        String firstName,
        String lastName,
        String phone,
        String email,
        Gender gender,
        LocalDate birthDate,
        String guardianPhone
) {
}
