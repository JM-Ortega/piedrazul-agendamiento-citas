package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.domain.Gender;

import java.time.LocalDate;
import java.util.UUID;

public class PatientResponse {

    private final UUID id;
    private final UUID userId;
    private final DocumentType documentType;
    private final String documentNumber;
    private final String firstName;
    private final String lastName;
    private final String phone;
    private final String email;
    private final Gender gender;
    private final LocalDate birthDate;
    private final String guardianPhone;

    public PatientResponse(
            UUID id,
            UUID userId,
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

    public DocumentType getDocumentType() {
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

    public Gender getGender() {
        return gender;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getGuardianPhone() {
        return guardianPhone;
    }
}