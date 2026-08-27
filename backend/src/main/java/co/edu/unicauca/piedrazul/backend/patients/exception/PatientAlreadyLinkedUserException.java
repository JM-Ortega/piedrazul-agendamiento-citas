package co.edu.unicauca.piedrazul.backend.patients.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class PatientAlreadyLinkedUserException extends PatientBusinessException {

    public PatientAlreadyLinkedUserException(UUID patientId) {
        super(
                "Patient with id " + patientId + " already has a linked user account",
                "PATIENT_ALREADY_LINKED_USER",
                HttpStatus.CONFLICT
        );
    }

    public PatientAlreadyLinkedUserException() {
        super("Patient already has a linked user account", "PATIENT_ALREADY_LINKED_USER", HttpStatus.CONFLICT);
    }
}