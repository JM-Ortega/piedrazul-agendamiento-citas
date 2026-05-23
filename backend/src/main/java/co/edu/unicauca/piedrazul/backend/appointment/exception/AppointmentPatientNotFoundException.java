package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class AppointmentPatientNotFoundException extends AppointmentBusinessException {
    public AppointmentPatientNotFoundException(String message) {
        super(message, "PATIENT_NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
