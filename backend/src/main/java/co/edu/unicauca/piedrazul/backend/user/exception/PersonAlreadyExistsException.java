package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

public class PersonAlreadyExistsException extends UserBusinessException {

    public PersonAlreadyExistsException(String message) {
        super(message, "PERSON_ALREADY_EXIST", HttpStatus.CONFLICT);
    }
}
