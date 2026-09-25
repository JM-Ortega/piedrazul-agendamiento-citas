package co.edu.unicauca.piedrazul.backend.appointment.application.scheduling;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.ResolvedPatient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutonomousPatientResolutionStrategyTest {

    @Mock
    private PatientConsultPort patientConsultPort;

    private AutonomousPatientResolutionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new AutonomousPatientResolutionStrategy(patientConsultPort);
    }

    @Test
    void shouldResolvePatientInfoFromConsultPortUsingContextIdPatient() {
        UUID idPatient = UUID.randomUUID();
        PatientInfo patientInfo = PatientInfo.of(
                DocumentType.CEDULA, "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "carlos@correo.com", null);
        when(patientConsultPort.findById(idPatient)).thenReturn(patientInfo);

        ResolvedPatient result = strategy.resolve(PatientSchedulingContext.autonomous(idPatient));

        assertThat(result.idPatient()).isEqualTo(idPatient);
        assertThat(result.patientInfo()).isEqualTo(patientInfo);
    }

    @Test
    void shouldThrowWhenContextIdPatientIsNull() {
        assertThatThrownBy(() -> strategy.resolve(new PatientSchedulingContext(
                null, null, null, null, null, null, null, null, null, null)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("El paciente es obligatorio");

        verifyNoInteractions(patientConsultPort);
    }
}
