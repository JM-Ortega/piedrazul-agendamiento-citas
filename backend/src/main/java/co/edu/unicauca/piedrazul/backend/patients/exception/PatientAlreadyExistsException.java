package co.edu.unicauca.piedrazul.backend.patients.exception;

public class PatientAlreadyExistsException extends RuntimeException {

    public PatientAlreadyExistsException(String documentNumber) {
        super("A patient with document number " + documentNumber + " already exists");
    }
}