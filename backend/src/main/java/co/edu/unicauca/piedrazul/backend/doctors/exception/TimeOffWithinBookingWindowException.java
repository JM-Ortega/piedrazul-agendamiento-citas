package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class TimeOffWithinBookingWindowException extends DoctorBusinessException {
    public TimeOffWithinBookingWindowException(String message) {
        super(message, "TIME_OFF_WITHIN_BOOKING_WINDOW", HttpStatus.CONFLICT);
    }
}
