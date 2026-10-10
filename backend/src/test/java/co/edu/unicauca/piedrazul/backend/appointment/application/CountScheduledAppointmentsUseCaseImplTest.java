package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CountScheduledAppointmentsUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    private CountScheduledAppointmentsUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new CountScheduledAppointmentsUseCaseImpl(appointmentRepository);
    }

    @Test
    void executeShouldDelegateToRepositoryWithAgendadaState() {
        LocalDate date = LocalDate.now().plusDays(1);
        when(appointmentRepository.countByDateAndState(date, AppointmentState.AGENDADA)).thenReturn(5L);

        long result = useCase.execute(date);

        assertThat(result).isEqualTo(5L);
    }
}
