package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class InvalidPersonNameException extends RuntimeException {
    public InvalidPersonNameException(String message) {
        super(message);
    }
}
