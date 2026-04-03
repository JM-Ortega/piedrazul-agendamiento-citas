package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import jakarta.validation.constraints.NotBlank;

public class RequestLinkUserAccountCodeRequest {

    @NotBlank
    private String documentNumber;

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }
}