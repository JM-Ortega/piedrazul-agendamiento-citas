package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class PatientAlreadyScheduledInSpecialtyException extends AppointmentBusinessException {
    public PatientAlreadyScheduledInSpecialtyException(String message) {
        super(message, "PATIENT_SPECIALTY_CONFLICT", HttpStatus.CONFLICT);
    }
}