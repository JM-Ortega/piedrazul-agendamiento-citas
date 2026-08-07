package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAppointmentStatesUseCase;

import java.util.Arrays;
import java.util.List;

public class GetAppointmentStatesUseCaseImpl implements GetAppointmentStatesUseCase {

    @Override
    public List<AppointmentState> getAppointmentStates() {
        return Arrays.asList(AppointmentState.values());
    }

}
