package co.edu.unicauca.piedrazul.backend.patients.exception;

import org.springframework.http.HttpStatus;

public class PatientAlreadyExistsException extends PatientBusinessException {

    public PatientAlreadyExistsException(String documentNumber) {
        super(
                "A patient with document number " + documentNumber + " already exists",
                "PATIENT_ALREADY_EXISTS",
                HttpStatus.CONFLICT
        );
    }
}