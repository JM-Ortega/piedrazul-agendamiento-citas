package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalData;

import java.util.UUID;

public interface AppointmentExternalService {

    AppointmentExternalData getAppointmentData(UUID idAppointment);

}
