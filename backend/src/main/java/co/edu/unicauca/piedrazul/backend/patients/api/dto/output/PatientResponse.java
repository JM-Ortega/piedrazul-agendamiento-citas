package co.edu.unicauca.piedrazul.backend.patients.api.dto.output;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;

import java.time.LocalDate;
import java.util.UUID;

public class PatientResponse {

    private final UUID id;
    private final UUID userId;
    private final PatientDocumentType documentType;
    private final String documentNumber;
    private final String firstName;
    private final String lastName;
    private final String phone;
    private final String email;
    private final PatientGender gender;
    private final LocalDate birthDate;
    private final String guardianPhone;

    public PatientResponse(
            UUID id,
            UUID userId,
            PatientDocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            PatientGender gender,
            LocalDate birthDate,
            String guardianPhone
    ) {
        this.id = id;
        this.userId = userId;
        this.documentType = documentType;
        this.documentNumber = documentNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.email = email;
        this.gender = gender;
        this.birthDate = birthDate;
        this.guardianPhone = guardianPhone;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public PatientDocumentType getDocumentType() {
        return documentType;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public PatientGender getGender() {
        return gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getGuardianPhone() {
        return guardianPhone;
    }
}