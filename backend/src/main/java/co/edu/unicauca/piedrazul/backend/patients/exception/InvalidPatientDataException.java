package co.edu.unicauca.piedrazul.backend.patients.exception;

import org.springframework.http.HttpStatus;

public class InvalidPatientDataException extends PatientBusinessException {

    public InvalidPatientDataException(String message) {
        super(message, "INVALID_PATIENT_DATA", HttpStatus.BAD_REQUEST);
    }
}