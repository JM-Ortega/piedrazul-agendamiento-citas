package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class DateConflictException extends DoctorBusinessException {
    public DateConflictException(String message) {
        super(message, "DATE_CONFLICT", HttpStatus.CONFLICT);
    }
}