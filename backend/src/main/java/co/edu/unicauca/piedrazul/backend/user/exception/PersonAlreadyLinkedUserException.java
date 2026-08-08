package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

public class PersonAlreadyLinkedUserException extends UserBusinessException {
    public PersonAlreadyLinkedUserException(String message) {
        super(message, "PERSON_ALREADY_LINKED", HttpStatus.CONFLICT);
    }
}
