package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class DoctorOnTimeOffException extends AppointmentBusinessException {
    public DoctorOnTimeOffException(String message) {
        super(message, "DOCTOR_ON_TIME_OFF", HttpStatus.CONFLICT);
    }
}
