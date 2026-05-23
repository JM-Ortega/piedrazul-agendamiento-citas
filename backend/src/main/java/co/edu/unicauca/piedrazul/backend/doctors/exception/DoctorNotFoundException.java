package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class DoctorNotFoundException extends DoctorBusinessException {
    public DoctorNotFoundException(String message) {
        super(message, "DOCTOR_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}