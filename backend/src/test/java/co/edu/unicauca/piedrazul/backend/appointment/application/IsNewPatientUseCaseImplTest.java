package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsNewPatientUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    
    @Mock
    private PatientConsultPort patientConsultPort;

    private IsNewPatientUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new IsNewPatientUseCaseImpl(appointmentRepository, patientConsultPort);
    }

    @Test
    void isNewPatientShouldReturnFalseWhenPatientHasScheduledOrAttendedAppointment() {
        UUID patientId = UUID.randomUUID();
        Collection<AppointmentState> expectedStates = EnumSet.of(AppointmentState.AGENDADA, AppointmentState.ATENDIDA);

        when(patientConsultPort.existsById(patientId)).thenReturn(true);
        when(appointmentRepository.existsByPatientIdAndStates(patientId, expectedStates)).thenReturn(true);

        boolean result = useCase.isNewPatient(patientId);

        assertThat(result).isFalse();

        ArgumentCaptor<Collection<AppointmentState>> statesCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(appointmentRepository).existsByPatientIdAndStates(eq(patientId), statesCaptor.capture());
        assertThat(statesCaptor.getValue()).containsExactlyInAnyOrder(AppointmentState.AGENDADA, AppointmentState.ATENDIDA);
    }

    @Test
    void isNewPatientShouldReturnTrueWhenPatientHasNoScheduledOrAttendedAppointments() {
        UUID patientId = UUID.randomUUID();
        Collection<AppointmentState> expectedStates = EnumSet.of(AppointmentState.AGENDADA, AppointmentState.ATENDIDA);

        when(patientConsultPort.existsById(patientId)).thenReturn(true);
        when(appointmentRepository.existsByPatientIdAndStates(patientId, expectedStates)).thenReturn(false);

        boolean result = useCase.isNewPatient(patientId);

        assertThat(result).isTrue();
    }

    @Test
    void isNewPatientShouldPropagatePatientNotFoundWhenPatientDoesNotExist() {
        UUID patientId = UUID.randomUUID();

        when(patientConsultPort.existsById(patientId)).thenReturn(false);

        boolean result = useCase.isNewPatient(patientId);
        assertThat(result).isTrue();
    }
}