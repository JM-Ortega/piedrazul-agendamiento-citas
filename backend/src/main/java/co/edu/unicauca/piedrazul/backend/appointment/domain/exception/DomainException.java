package co.edu.unicauca.piedrazul.backend.appointment.domain.exception;

import org.springframework.http.HttpStatus;

public class DomainException extends AppointmentBusinessException {

    public DomainException(String message) {
        this(message, "DOMAIN_CONFLICT");
    }

    protected DomainException(String message, String errorCode) {
        super(message, errorCode, HttpStatus.CONFLICT);
    }
}