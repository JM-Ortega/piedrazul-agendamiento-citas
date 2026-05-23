package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class DoctorValidationException extends DoctorBusinessException {
    public DoctorValidationException(String message) {
        super(message, "DOCTOR_VALIDATION_ERROR", HttpStatus.BAD_REQUEST);
    }
}