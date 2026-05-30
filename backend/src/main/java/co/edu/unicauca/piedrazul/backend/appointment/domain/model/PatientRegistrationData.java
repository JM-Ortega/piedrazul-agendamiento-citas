package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

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