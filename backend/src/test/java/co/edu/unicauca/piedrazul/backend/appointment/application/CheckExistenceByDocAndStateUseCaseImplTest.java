package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckExistenceByDocAndStateUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    private CheckExistenceByDocAndStateUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new CheckExistenceByDocAndStateUseCaseImpl(appointmentRepository);
    }

    @Test
    void executeShouldDelegateToRepository() {
        UUID idDoctor = UUID.randomUUID();
        when(appointmentRepository.existsByDoctorAndState(idDoctor, AppointmentState.AGENDADA)).thenReturn(true);

        boolean result = useCase.execute(idDoctor, AppointmentState.AGENDADA);

        assertThat(result).isTrue();
    }
}
