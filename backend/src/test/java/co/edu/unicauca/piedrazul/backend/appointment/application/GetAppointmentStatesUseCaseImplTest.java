package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GetAppointmentStatesUseCaseImplTest {

    private final GetAppointmentStatesUseCaseImpl useCase = new GetAppointmentStatesUseCaseImpl();

    @Test
    void getAppointmentStatesShouldReturnAllEnumValues() {
        List<AppointmentState> result = useCase.getAppointmentStates();

        assertThat(result).containsExactly(AppointmentState.values());
    }
}
