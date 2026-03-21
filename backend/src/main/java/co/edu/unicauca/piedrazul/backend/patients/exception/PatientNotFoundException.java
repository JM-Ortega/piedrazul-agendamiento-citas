package co.edu.unicauca.piedrazul.backend.patients.exception;

public class PatientNotFoundException extends RuntimeException {

    public PatientNotFoundException(String documentNumber) {
        super("Patient with document number " + documentNumber + " was not found");
    }

    public PatientNotFoundException() {
        super("Patient was not found");
    }
}