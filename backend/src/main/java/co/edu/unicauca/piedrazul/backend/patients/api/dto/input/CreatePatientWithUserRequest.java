package co.edu.unicauca.piedrazul.backend.patients.api.dto.input;

import co.edu.unicauca.piedrazul.backend.jackson.normalization.NormalizeName;
import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.jackson.validation.ValidDocument;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@ValidDocument(
        documentField = "identification",
        typeField = "identificationType"
)
public class CreatePatientWithUserRequest {

    @NotBlank
    @Size(min = 4, max = 50)
    @Pattern(regexp = "^[A-Za-z0-9._-]{4,50}$")
    @Sanitize
    private String username;

    @NotNull
    private IdentificationType identificationType;

    @NotBlank
    @Size(max = 20)
    @Sanitize
    private String identification;

    @NotBlank
    @Size(min = 2, max = 60)
    @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
    @Sanitize
    @NormalizeName
    private String firstName;

    @NotBlank
    @Size(min = 2, max = 60)
    @Pattern(regexp = "^[\\p{L} '-]{2,60}$")
    @Sanitize
    @NormalizeName
    private String lastName;

    // Número Colombiano
    @Pattern(regexp = "^[0-9]{10}$")
    @NotBlank
    @Sanitize
    private String phone;

    @Email
    @Size(max = 120)
    @Sanitize
    private String email;

    @NotNull
    private PatientSex sex;

    @NotNull
    private LocalDate birthDate;

    // Número Colombiano
    @Pattern(regexp = "^[0-9]{10}$")
    @Sanitize
    private String guardianPhone;

    @NotBlank
    @Size(min = 6, max = 100)
    private String password;

    public String getUsername() {
        return username;
    }

    public IdentificationType getIdentificationType() {
        return identificationType;
    }

    public String getIdentification() {
        return identification;
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

    public PatientSex getSex() {
        return sex;
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
