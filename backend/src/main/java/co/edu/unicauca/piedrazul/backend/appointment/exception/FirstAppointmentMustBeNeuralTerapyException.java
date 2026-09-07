package co.edu.unicauca.piedrazul.backend.appointment.exception;

import org.springframework.http.HttpStatus;

public class FirstAppointmentMustBeNeuralTerapyException extends AppointmentBusinessException {

    public FirstAppointmentMustBeNeuralTerapyException(String message) {
        super(message, "FIRST_APPOINTMENT_GENERAL_MEDICINE", HttpStatus.CONFLICT);
    }
}