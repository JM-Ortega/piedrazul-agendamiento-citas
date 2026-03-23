package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

public class SlotNotAvailableException extends RuntimeException {
    public SlotNotAvailableException(String message) {
        super(message);
    }
}
