package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class OnlyOneAppointmentPerMonthException extends AppointmentBusinessException {
    public OnlyOneAppointmentPerMonthException(String message) {
        super(message, "ONE_APPOINTMENT_PER_MONTH", HttpStatus.CONFLICT);
    }
}

