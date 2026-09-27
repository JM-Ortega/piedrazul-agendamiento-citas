package co.edu.unicauca.piedrazul.backend.audit.exception;

import co.edu.unicauca.piedrazul.backend.shared.BusinessException;
import org.springframework.http.HttpStatus;

public class InvalidAuditCriteriaException extends RuntimeException implements BusinessException {

    public InvalidAuditCriteriaException(String message) {
        super(message);
    }

    @Override
    public String getErrorCode() {
        return "INVALID_AUDIT_CRITERIA";
    }

    @Override
    public HttpStatus getStatus() {
        return HttpStatus.BAD_REQUEST;
    }

    @Override
    public String getModule() {
        return "audit";
    }
}
