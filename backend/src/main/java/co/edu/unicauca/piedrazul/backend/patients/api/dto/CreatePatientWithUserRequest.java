package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public class CreatePatientWithUserRequest {

    @NotBlank
    private String username;

    @NotNull
    private PatientDocumentType documentType;

    @NotBlank
    private String documentNumber;

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    @NotBlank
    private String phone;

    private String email;

    @NotNull
    private PatientGender gender;

    @NotNull
    private LocalDate birthDate;

    private String guardianPhone;

    @NotBlank
    private String password;

    public String getUsername() {
        return username;
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

    public String getPassword() {
    return password;
}
}