package co.edu.unicauca.piedrazul.backend.medicalCheckup.exception;

import org.springframework.http.HttpStatus;

public class MedicalCheckupAlreadyExistsException extends MedicalCheckupBusinessException {
    public MedicalCheckupAlreadyExistsException(String message) {
        super(message, "CLINICAL_HISTORY_ALREADY_EXISTS", HttpStatus.CONFLICT);
    }
}
