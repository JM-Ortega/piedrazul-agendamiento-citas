package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

public class InvalidRoleAssignmentException extends UserBusinessException {
    public InvalidRoleAssignmentException(String message) {
        super(message, "INVALID_ROLE_ASSIGNMENT", HttpStatus.BAD_REQUEST);
    }
}
