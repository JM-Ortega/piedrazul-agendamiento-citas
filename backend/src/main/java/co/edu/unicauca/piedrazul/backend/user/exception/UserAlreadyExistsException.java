package co.edu.unicauca.piedrazul.backend.user.exception;

public class UserAlreadyExistsException extends UserException {

    public UserAlreadyExistsException(String username) {
        super("User with username '" + username + "' already exists");
    }
}