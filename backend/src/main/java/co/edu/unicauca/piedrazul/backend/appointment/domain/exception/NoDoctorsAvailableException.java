package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class NoDoctorsAvailableException extends RuntimeException {
    public NoDoctorsAvailableException(String message) {
        super(message);
    }
}
