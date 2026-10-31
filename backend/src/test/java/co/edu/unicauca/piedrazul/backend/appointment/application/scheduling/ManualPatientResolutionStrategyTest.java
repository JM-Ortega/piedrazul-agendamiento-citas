package co.edu.unicauca.piedrazul.backend.appointment.application.scheduling;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientProvisioningPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.ResolvedPatient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManualPatientResolutionStrategyTest {

    @Mock
    private PatientProvisioningPort patientProvisioningPort;

    private ManualPatientResolutionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new ManualPatientResolutionStrategy(patientProvisioningPort);
    }

    @Test
    void shouldMapContextFieldsIntoRegistrationDataAndDelegateToProvisioningPort() {
        PatientSchedulingContext context = PatientSchedulingContext.manual(
                DocumentType.CEDULA, "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "carlos@correo.com", null);
        UUID idPatient = UUID.randomUUID();
        PatientInfo canonicalInfo = PatientInfo.of(
                DocumentType.CEDULA, "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "carlos@correo.com", null);
        when(patientProvisioningPort.resolveOrRegister(any()))
                .thenReturn(new PatientSnapshot(idPatient, canonicalInfo));

        strategy.resolve(context);

        ArgumentCaptor<PatientRegistrationData> captor = ArgumentCaptor.forClass(PatientRegistrationData.class);
        verify(patientProvisioningPort).resolveOrRegister(captor.capture());
        PatientRegistrationData sent = captor.getValue();
        assertThat(sent.documentType()).isEqualTo(DocumentType.CEDULA);
        assertThat(sent.documentNumber()).isEqualTo("12345678");
        assertThat(sent.firstName()).isEqualTo("Carlos");
        assertThat(sent.lastName()).isEqualTo("Gomez");
        assertThat(sent.phone()).isEqualTo("3001234567");
        assertThat(sent.email()).isEqualTo("carlos@correo.com");
        assertThat(sent.gender()).isEqualTo(Gender.MASCULINO);
        assertThat(sent.birthDate()).isEqualTo(LocalDate.of(1990, 6, 15));
        assertThat(sent.guardianPhone()).isNull();
    }

    @Test
    void shouldReturnResolvedPatientBuiltFromCanonicalSnapshotNotFromTheContext() {
        PatientSchedulingContext context = PatientSchedulingContext.manual(
                DocumentType.CEDULA, "12345678", "Ana Formulario", "Ruiz Formulario", "3001112222",
                Gender.FEMENINO, LocalDate.of(1990, 6, 15), "formulario@example.com", null);
        UUID idPatient = UUID.randomUUID();
        PatientInfo canonicalInfo = PatientInfo.of(
                DocumentType.CEDULA, "12345678", "Ana Canonica", "Ruiz Canonica", "3009998888",
                Gender.FEMENINO, LocalDate.of(1990, 6, 15), "canonico@example.com", null);
        when(patientProvisioningPort.resolveOrRegister(any()))
                .thenReturn(new PatientSnapshot(idPatient, canonicalInfo));

        ResolvedPatient result = strategy.resolve(context);

        assertThat(result.idPatient()).isEqualTo(idPatient);
        assertThat(result.patientInfo().getFirstName()).isEqualTo("Ana Canonica");
        assertThat(result.patientInfo().getPhone()).isEqualTo("3009998888");
    }
}
