package co.edu.unicauca.piedrazul.backend.appointment.exception;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.AppointmentBusinessException;
import org.springframework.http.HttpStatus;

public class FirstAppointmentMustBeGeneralMedicineException extends AppointmentBusinessException {

    public FirstAppointmentMustBeGeneralMedicineException(String message) {
        super(message, "FIRST_APPOINTMENT_GENERAL_MEDICINE", HttpStatus.CONFLICT);
    }
}