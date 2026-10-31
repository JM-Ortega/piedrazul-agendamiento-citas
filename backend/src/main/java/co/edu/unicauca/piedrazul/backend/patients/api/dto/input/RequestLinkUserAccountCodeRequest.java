package co.edu.unicauca.piedrazul.backend.patients.api.dto.input;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RequestLinkUserAccountCodeRequest {

    @NotBlank
    @Size(max = 12)
    @Pattern(regexp = "^[A-Za-z0-9._-]{5,30}$")
    @Sanitize
    private String identification;

    public String getIdentification() {
        return identification;
    }

    public void setIdentification(String identification) {
        this.identification = identification;
    }
}
