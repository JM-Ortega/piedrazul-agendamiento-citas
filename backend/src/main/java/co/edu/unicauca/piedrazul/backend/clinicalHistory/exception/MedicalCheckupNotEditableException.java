package co.edu.unicauca.piedrazul.backend.clinicalHistory.exception;

import org.springframework.http.HttpStatus;

public class MedicalCheckupNotEditableException extends MedicalCheckupBusinessException {
    public MedicalCheckupNotEditableException(String message) {
        super(message, "MEDICAL_CHECKUP_NOT_EDITABLE", HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
