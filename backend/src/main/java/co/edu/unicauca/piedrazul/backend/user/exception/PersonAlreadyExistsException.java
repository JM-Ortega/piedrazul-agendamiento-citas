package co.edu.unicauca.piedrazul.backend.user.exception;

public class PersonAlreadyExistsException extends UserException {

    public PersonAlreadyExistsException(String identification) {
        super("Ya existe una persona con identificación '" + identification + "'");
    }
}
