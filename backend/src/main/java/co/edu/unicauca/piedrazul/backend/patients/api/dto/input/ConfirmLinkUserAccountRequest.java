package co.edu.unicauca.piedrazul.backend.patients.api.dto.input;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Representa la solicitud de confirmación de la habilitación de acceso.
 *
 * <p>Los campos opcionales son condicionales: {@code password} se requiere cuando
 * la persona no tiene cuenta vinculada; {@code sex} y {@code birthDate} cuando
 * todavía no existe el paciente; {@code guardianPhone}, además, cuando el paciente
 * es menor de edad.
 */
public class ConfirmLinkUserAccountRequest {

    @NotBlank
    @Size(max = 12)
    @Pattern(regexp = "^[A-Za-z0-9._-]{5,30}$")
    @Sanitize
    private String identification;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9]{4,12}$")
    @Sanitize
    private String code;

    @Size(min = 6, max = 100)
    private String password;

    private PatientSex sex;

    private LocalDate birthDate;

    @Pattern(regexp = "^[0-9]{10}$")
    @Sanitize
    private String guardianPhone;

    public String getIdentification() {
        return identification;
    }

    public void setIdentification(String identification) {
        this.identification = identification;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public PatientSex getSex() {
        return sex;
    }

    public void setSex(PatientSex sex) {
        this.sex = sex;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public String getGuardianPhone() {
        return guardianPhone;
    }

    public void setGuardianPhone(String guardianPhone) {
        this.guardianPhone = guardianPhone;
    }
}
