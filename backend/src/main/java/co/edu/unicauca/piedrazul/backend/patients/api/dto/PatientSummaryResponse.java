package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import java.util.UUID;

public class PatientSummaryResponse {

    private UUID id;
    private String documentNumber;
    private String firstName;
    private String lastName;

    public PatientSummaryResponse() {
    }

    public PatientSummaryResponse(UUID id, String documentNumber, String firstName, String lastName) {
        this.id = id;
        this.documentNumber = documentNumber;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public UUID getId() {
        return id;
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

    public void setId(UUID id) {
        this.id = id;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }
}