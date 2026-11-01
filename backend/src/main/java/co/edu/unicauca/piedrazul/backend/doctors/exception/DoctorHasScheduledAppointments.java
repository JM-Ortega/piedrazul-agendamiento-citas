package co.edu.unicauca.piedrazul.backend.doctors.exception;

import org.springframework.http.HttpStatus;

public class DoctorHasScheduledAppointments extends DoctorBusinessException {
    public DoctorHasScheduledAppointments(String message) {
        super(message, "DOCTOR_SCHEDULED_APPOINTMENTS", HttpStatus.CONFLICT);
    }
}
