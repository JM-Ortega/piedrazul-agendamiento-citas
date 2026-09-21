package co.edu.unicauca.piedrazul.backend.clinicalHistory.exception;

import co.edu.unicauca.piedrazul.backend.shared.BusinessException;
import org.springframework.http.HttpStatus;

public abstract class MedicalCheckupBusinessException extends RuntimeException
        implements BusinessException {

    private final String errorCode;
    private final HttpStatus status;

    protected MedicalCheckupBusinessException(String message, String errorCode, HttpStatus status) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    @Override
    public String getErrorCode() { return errorCode; }

    @Override
    public HttpStatus getStatus() { return status; }

    @Override
    public String getModule() { return "clinicalHistory"; }
}