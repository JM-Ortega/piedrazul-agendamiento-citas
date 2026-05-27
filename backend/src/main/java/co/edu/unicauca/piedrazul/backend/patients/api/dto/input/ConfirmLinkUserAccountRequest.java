package co.edu.unicauca.piedrazul.backend.patients.api.dto.input;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ConfirmLinkUserAccountRequest {

    @NotBlank
    @Size(max = 12)
    @Pattern(regexp = "^[A-Za-z0-9._-]{5,30}$")
    @Sanitize
    private String documentNumber;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9]{4,12}$")
    @Sanitize
    private String code;

    @NotBlank
    @Size(min = 6, max = 100)
    private String password;

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
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
}