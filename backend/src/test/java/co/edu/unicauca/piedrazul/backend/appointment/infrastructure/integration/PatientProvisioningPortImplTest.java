package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.RegisterPatientCommand;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Frontera de agendamiento hacia el módulo de pacientes.
 */
@ExtendWith(MockitoExtension.class)
class PatientProvisioningPortImplTest {

    private static final LocalDate BIRTH_DATE = LocalDate.now().minusYears(30);

    @Mock
    private PatientModuleApi patientModuleApi;

    @InjectMocks
    private PatientProvisioningPortImpl port;

    private final UUID personId = UUID.randomUUID();

    private PatientRegistrationData formData() {
        return new PatientRegistrationData(
                DocumentType.CEDULA, "1061234567",
                "Ana Formulario", "Ruiz Formulario", "3001112222",
                "formulario@example.com", Gender.FEMENINO, BIRTH_DATE, null);
    }

    private PatientData canonical() {
        return new PatientData(
                personId, UUID.randomUUID(), IdentificationType.CEDULA, "1061234567",
                "Ana Canónica", "Ruiz Canónica", "3009998888", "canonico@example.com",
                PatientSex.FEMENINO, BIRTH_DATE, null);
    }

    @Test
    void shouldTranslateRegistrationDataIntoTheModuleCommand() {
        when(patientModuleApi.resolveOrRegisterPatient(any())).thenReturn(canonical());

        port.resolveOrRegister(formData());

        ArgumentCaptor<RegisterPatientCommand> command =
                ArgumentCaptor.forClass(RegisterPatientCommand.class);
        verify(patientModuleApi).resolveOrRegisterPatient(command.capture());

        RegisterPatientCommand sent = command.getValue();
        assertEquals(IdentificationType.CEDULA, sent.identificationType());
        assertEquals("1061234567", sent.documentNumber());
        assertEquals("Ana Formulario", sent.firstName());
        assertEquals("3001112222", sent.phone());
        assertEquals(PatientSex.FEMENINO, sent.sex());
        assertEquals(BIRTH_DATE, sent.birthDate());
    }

    @Test
    void shouldBuildTheSnapshotFromCanonicalDataNotFromTheForm() {
        when(patientModuleApi.resolveOrRegisterPatient(any())).thenReturn(canonical());

        PatientSnapshot snapshot = port.resolveOrRegister(formData());

        assertEquals(personId, snapshot.idPatient());
        // Los datos que viajan a la cita son los que devolvió el módulo de pacientes.
        assertEquals("Ana Canónica", snapshot.patientInfo().getFirstName());
        assertEquals("3009998888", snapshot.patientInfo().getPhone());
        assertEquals("canonico@example.com", snapshot.patientInfo().getEmail());
    }
}
