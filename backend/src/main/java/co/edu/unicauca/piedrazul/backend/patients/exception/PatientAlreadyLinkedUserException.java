package co.edu.unicauca.piedrazul.backend.patients.exception;

import org.springframework.http.HttpStatus;

/**
 * El acceso del paciente ya está habilitado. El detalle se devuelve en endpoints públicos, así que
 * no incluye identificadores internos.
 */
public class PatientAlreadyLinkedUserException extends PatientBusinessException {

    public PatientAlreadyLinkedUserException() {
        super("El paciente ya tiene una cuenta de usuario vinculada", "PATIENT_ALREADY_LINKED_USER", HttpStatus.CONFLICT);
    }
}