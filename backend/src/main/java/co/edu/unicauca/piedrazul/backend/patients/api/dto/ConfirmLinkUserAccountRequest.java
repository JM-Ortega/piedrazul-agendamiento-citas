package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import jakarta.validation.constraints.NotBlank;

public class ConfirmLinkUserAccountRequest {

    @NotBlank
    private String documentNumber;

    @NotBlank
    private String code;

    @NotBlank
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