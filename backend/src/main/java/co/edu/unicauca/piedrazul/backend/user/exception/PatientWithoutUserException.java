package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class PatientWithoutUserException extends UserBusinessException {

    public PatientWithoutUserException(UUID patientId) {
        super(
                "El paciente con id " + patientId + " no tiene una cuenta de usuario",
                "PATIENT_WITHOUT_USER",
                HttpStatus.CONFLICT
        );
    }
}
