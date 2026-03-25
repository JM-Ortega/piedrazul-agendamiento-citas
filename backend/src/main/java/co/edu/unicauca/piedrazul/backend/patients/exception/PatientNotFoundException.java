package co.edu.unicauca.piedrazul.backend.patients.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class PatientNotFoundException extends PatientBusinessException {

    public PatientNotFoundException(UUID id) {
        super("Patient not found with id: " + id, "PATIENT_NOT_FOUND", HttpStatus.NOT_FOUND);
    }

    public PatientNotFoundException(String documentNumber) {
        super(
                "Patient not found with document number: " + documentNumber,
                "PATIENT_NOT_FOUND",
                HttpStatus.NOT_FOUND
        );
    }
}