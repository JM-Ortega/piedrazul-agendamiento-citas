package co.edu.unicauca.piedrazul.backend.shared.domain.exception;

import org.springframework.http.HttpStatus;

public interface BusinessException {
    String getErrorCode();

    HttpStatus getStatus();

    String getModule();
}
