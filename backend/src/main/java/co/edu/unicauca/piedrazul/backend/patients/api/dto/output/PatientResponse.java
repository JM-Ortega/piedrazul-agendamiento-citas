package co.edu.unicauca.piedrazul.backend.patients.api.dto.output;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;

import java.time.LocalDate;
import java.util.UUID;

public class PatientResponse {

    private final UUID id;
    private final UUID userId;
    private final IdentificationType identificationType;
    private final String identification;
    private final String firstName;
    private final String lastName;
    private final String phone;
    private final String email;
    private final PatientSex sex;
    private final LocalDate birthDate;
    private final String guardianPhone;

    public PatientResponse(
            UUID id,
            UUID userId,
            IdentificationType identificationType,
            String identification,
            String firstName,
            String lastName,
            String phone,
            String email,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    ) {
        this.id = id;
        this.userId = userId;
        this.identificationType = identificationType;
        this.identification = identification;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.email = email;
        this.sex = sex;
        this.birthDate = birthDate;
        this.guardianPhone = guardianPhone;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
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
}
