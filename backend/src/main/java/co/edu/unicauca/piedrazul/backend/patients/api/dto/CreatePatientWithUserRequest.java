package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import co.edu.unicauca.piedrazul.backend.config.security.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.config.security.validation.ValidDocument;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@ValidDocument
public class CreatePatientWithUserRequest {

    @NotBlank
    @Size(min = 4, max = 50)
    @Pattern(regexp = "^[A-Za-z0-9._-]{4,50}$")
    @Sanitize
    private String username;

    @NotNull
    private PatientDocumentType documentType;

    @NotBlank
    @Size(max = 20)
    @Sanitize
    private String documentNumber;

    @NotBlank
    @Size(min = 2, max = 60)
    @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
    @Sanitize
    private String firstName;

    @NotBlank
    @Size(min = 2, max = 60)
    @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
    @Sanitize
    private String lastName;

    @NotBlank
    @Pattern(regexp = "^[0-9]{7,15}$")
    @Sanitize
    private String phone;

    @Email
    @Size(max = 120)
    @Sanitize
    private String email;

    @NotNull
    private PatientGender gender;

    @NotNull
    private LocalDate birthDate;

    @Pattern(regexp = "^[0-9]{7,15}$")
    @Sanitize
    private String guardianPhone;

    @NotBlank
    @Size(min = 8, max = 100)
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