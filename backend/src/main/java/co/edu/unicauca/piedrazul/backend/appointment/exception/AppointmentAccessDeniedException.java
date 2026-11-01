package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class AppointmentAccessDeniedException extends AppointmentBusinessException {
    public AppointmentAccessDeniedException(String message) {
        super(message, "APPOINTMENT_ACCESS_DENIED", HttpStatus.FORBIDDEN);
    }
}
