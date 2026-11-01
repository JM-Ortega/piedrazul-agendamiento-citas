package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class DoctorConfigInconsistentException extends AppointmentBusinessException {
    public DoctorConfigInconsistentException(String message) {
        super(message, "DOCTOR_BAD_CONFIG", HttpStatus.BAD_REQUEST);
    }
}
