package co.edu.unicauca.piedrazul.backend.patients.exception;

import java.util.UUID;

public class PatientAlreadyLinkedUserException extends RuntimeException {

    public PatientAlreadyLinkedUserException(UUID patientId) {
        super("Patient with id " + patientId + " already has a linked user account");
    }

    public PatientAlreadyLinkedUserException() {
        super("Patient already has a linked user account");
    }
}