package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

import org.springframework.http.HttpStatus;

public class SlotNotAvailableException extends AppointmentBusinessException {
    public SlotNotAvailableException(String message) {
        super(message, "SLOT_CONFLICT", HttpStatus.CONFLICT);
    }
}
