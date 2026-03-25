package co.edu.unicauca.piedrazul.backend.shared;

import org.springframework.http.HttpStatus;

public interface BusinessException {
    String getErrorCode();

    HttpStatus getStatus();

    String getModule();
}
