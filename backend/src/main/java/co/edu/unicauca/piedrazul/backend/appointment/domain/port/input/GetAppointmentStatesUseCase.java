package co.edu.unicauca.piedrazul.backend.appointment.domain.port.input;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;

import java.util.List;

public interface GetAppointmentStatesUseCase {
    List<AppointmentState> getAppointmentStates();
}
