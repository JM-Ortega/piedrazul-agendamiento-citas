package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class DoctorScheduleValidationException extends DoctorBusinessException {
    public DoctorScheduleValidationException(String message) {
        super(message, "DOCTOR_SCHEDULE_VALIDATION_ERROR", HttpStatus.BAD_REQUEST);
    }
}