package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UpdateAutonomousSchedulingUseCaseImplTest {

    @Mock
    private AppointmentConfigRepository appointmentConfigRepository;

    private UpdateAutonomousSchedulingUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateAutonomousSchedulingUseCaseImpl(appointmentConfigRepository);
    }

    @Test
    void setEnabledAutonomousShouldDelegateToConfigRepositoryWhenEnabling() {
        useCase.setEnabledAutonomous(true);

        verify(appointmentConfigRepository).setAutonomousSchedulingEnabled(true);
    }

    @Test
    void setEnabledAutonomousShouldDelegateToConfigRepositoryWhenDisabling() {
        useCase.setEnabledAutonomous(false);

        verify(appointmentConfigRepository).setAutonomousSchedulingEnabled(false);
    }
}
