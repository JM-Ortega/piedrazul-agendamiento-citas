package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class DoctorInvalidSpecialty extends DoctorBusinessException {
    public DoctorInvalidSpecialty(String message) {
        super(message, "INVALID_SPECIALTY", HttpStatus.BAD_REQUEST);
    }
}
