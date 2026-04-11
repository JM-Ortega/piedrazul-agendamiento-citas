package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.dto.AppointmentExternalData;

import java.util.UUID;

public interface AppointmentExternalService {

    AppointmentExternalData getAppointmentData(UUID idAppointment);

}
