package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

public class DoctorRoleRequiredException extends UserBusinessException {
    public DoctorRoleRequiredException(String message) {
        super(message, "ROLE_REQUIRED", HttpStatus.BAD_REQUEST);
    }
}
