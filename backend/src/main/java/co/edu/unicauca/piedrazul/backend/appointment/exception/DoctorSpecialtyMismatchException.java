package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class DoctorSpecialtyMismatchException extends AppointmentBusinessException {
    public DoctorSpecialtyMismatchException(String message) {
        super(message, "DOCTOR_SPECIALTY_MISMATCH", HttpStatus.CONFLICT);
    }
}
