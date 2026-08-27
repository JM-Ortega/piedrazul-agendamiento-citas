package co.edu.unicauca.piedrazul.backend.patients.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class PatientAlreadyLinkedUserException extends PatientBusinessException {

    public PatientAlreadyLinkedUserException(UUID patientId) {
        super(
                "El paciente con id " + patientId + " ya tiene una cuenta de usuario vinculada",
                "PATIENT_ALREADY_LINKED_USER",
                HttpStatus.CONFLICT
        );
    }

    public PatientAlreadyLinkedUserException() {
        super("El paciente ya tiene una cuenta de usuario vinculada", "PATIENT_ALREADY_LINKED_USER", HttpStatus.CONFLICT);
    }
}