package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAutonomousSchedulingContidionUseCaseImplTest {

    @Mock
    private AppointmentConfigRepository appointmentConfigRepository;

    private GetAutonomousSchedulingContidionUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetAutonomousSchedulingContidionUseCaseImpl(appointmentConfigRepository);
    }

    @Test
    void isAutonomousSchedulingEnabledShouldDelegateToConfigRepository() {
        when(appointmentConfigRepository.isAutonomousSchedulingEnabled()).thenReturn(true);

        assertThat(useCase.isAutonomousSchedulingEnabled()).isTrue();
    }
}
