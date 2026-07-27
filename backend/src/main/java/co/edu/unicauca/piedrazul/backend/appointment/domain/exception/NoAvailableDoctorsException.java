package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class NoAvailableDoctorsException extends DomainException {
    public NoAvailableDoctorsException(String message) {
        super(message, "NO_AVAILABLE_DOCTORS");
    }
}