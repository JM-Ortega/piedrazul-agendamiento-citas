package co.edu.unicauca.piedrazul.backend.patients.api.dto.output;

import java.util.UUID;

public class PatientSummaryResponse {

    private UUID id;
    private String identification;
    private String firstName;
    private String lastName;

    public PatientSummaryResponse() {
    }

    public PatientSummaryResponse(UUID id, String identification, String firstName, String lastName) {
        this.id = id;
        this.identification = identification;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public UUID getId() {
        return id;
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

    public void setId(UUID id) {
        this.id = id;
    }

    public void setIdentification(String identification) {
        this.identification = identification;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }
}
