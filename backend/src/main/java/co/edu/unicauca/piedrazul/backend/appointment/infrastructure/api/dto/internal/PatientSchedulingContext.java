package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record PatientSchedulingContext(
        UUID idPatient,
        DocumentType documentType,
        String documentNumber,
        String firstName,
        String lastName,
        String phone,
        Gender gender,
        LocalDate birthDate,
        String email,
        String guardianPhone
) {
    public static PatientSchedulingContext manual(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            Gender gender,
            LocalDate birthDate,
            String email,
            String guardianPhone) {

        return new PatientSchedulingContext(
                null,
                Objects.requireNonNull(documentType, "El tipo de documento es obligatorio"),
                Objects.requireNonNull(documentNumber, "El número de documento es obligatorio"),
                Objects.requireNonNull(firstName, "El nombre es obligatorio"),
                Objects.requireNonNull(lastName, "El apellido es obligatorio"),
                Objects.requireNonNull(phone, "El teléfono es obligatorio"),
                Objects.requireNonNull(gender, "El género es obligatorio"),
                Objects.requireNonNull(birthDate, "La fecha de nacimiento es obligatoria"),
                email,
                guardianPhone
        );
    }

    public static PatientSchedulingContext autonomous(UUID idPatient) {
        return new PatientSchedulingContext(
                Objects.requireNonNull(idPatient, "El paciente es obligatorio"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}