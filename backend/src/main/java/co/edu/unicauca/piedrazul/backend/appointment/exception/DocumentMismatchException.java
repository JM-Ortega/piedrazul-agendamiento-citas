package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class DocumentMismatchException extends AppointmentBusinessException {
    public DocumentMismatchException(String message) {
        super(message, "DOCUMENT_MISMATCH", HttpStatus.CONFLICT);
    }
}
