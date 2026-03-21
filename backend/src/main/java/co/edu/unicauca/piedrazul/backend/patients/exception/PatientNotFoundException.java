package co.edu.unicauca.piedrazul.backend.patients.exception;

import java.util.UUID;

public class PatientNotFoundException extends RuntimeException {

    public PatientNotFoundException(UUID id) {
        super("Patient not found with id: " + id);
    }

    public PatientNotFoundException(String documentNumber) {
        super("Patient not found with document number: " + documentNumber);
    }
}