package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class AppointmentSchedulingDisableException extends AppointmentBusinessException {
    public AppointmentSchedulingDisableException(String message) {
        super(message, "AUTOMOUS_SCHEDULING_DISABLED", HttpStatus.CONFLICT);
    }
}
