package co.edu.unicauca.piedrazul.backend.appointment.exception;


import org.springframework.http.HttpStatus;

public class NoAvailableDoctorsException extends AppointmentBusinessException {
    public NoAvailableDoctorsException(String message) {
        super(message, "NO_AVAILABLE_DOCTORS", HttpStatus.NOT_FOUND);
    }
}