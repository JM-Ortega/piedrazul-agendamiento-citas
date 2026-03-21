package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class GuardianRequiredException extends RuntimeException {
    public GuardianRequiredException(String message) {
        super(message);
    }
}
