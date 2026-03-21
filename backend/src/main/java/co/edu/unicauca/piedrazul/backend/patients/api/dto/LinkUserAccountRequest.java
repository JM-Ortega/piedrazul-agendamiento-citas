package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import jakarta.validation.constraints.NotBlank;

public class LinkUserAccountRequest {

    @NotBlank
    private String documentNumber;

    @NotBlank
    private String username;

    public LinkUserAccountRequest() {
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}