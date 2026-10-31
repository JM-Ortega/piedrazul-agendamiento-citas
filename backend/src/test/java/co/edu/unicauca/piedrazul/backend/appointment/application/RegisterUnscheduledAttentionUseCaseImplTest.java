package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.ClinicalHistoryPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.ResolvedPatient;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterUnscheduledAttentionUseCaseImplTest {

    @Mock
    private DoctorConfigConsultPort doctorConfigConsultPort;
    @Mock
    private ClinicalHistoryPort clinicalHistoryPort;
    @Mock
    private ManualPatientResolutionStrategy manualPatientResolutionStrategy;
    @Mock
    private AppointmentRepository appointmentRepository;

    private RegisterUnscheduledAttentionUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new RegisterUnscheduledAttentionUseCaseImpl(
                doctorConfigConsultPort, clinicalHistoryPort, manualPatientResolutionStrategy, appointmentRepository);
    }

    private PatientSchedulingContext manualContext() {
        return PatientSchedulingContext.manual(
                DocumentType.CEDULA, "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "carlos@correo.com", null);
    }

    private PatientInfo patientInfo() {
        return PatientInfo.of(
                DocumentType.CEDULA, "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "carlos@correo.com", null);
    }

    /** Simula la asignación de id que hace la persistencia real al guardar. */
    private void stubSaveAssigningId() {
        when(appointmentRepository.save(any())).thenAnswer(invocation -> {
            Appointment toSave = invocation.getArgument(0);
            return Appointment.reconstruct(
                    UUID.randomUUID(), toSave.getIdDoctor(), toSave.getIdPatient(), toSave.getSpecialty(),
                    toSave.getAppointmentState(), toSave.getDate(), toSave.getStartTime(),
                    toSave.getSchedulingOrigin());
        });
    }

    @Test
    void shouldRegisterAppointmentWithoutClinicalHistoryWhenCheckupIsBlank() {
        UUID idDoctor = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientSchedulingContext context = manualContext();
        when(manualPatientResolutionStrategy.resolve(context))
                .thenReturn(new ResolvedPatient(idPatient, patientInfo()));
        stubSaveAssigningId();

        UUID appointmentId = useCase.register(idDoctor, context, SpecialtyCode.FISIOTERAPIA, "   ");

        ArgumentCaptor<Appointment> captor = ArgumentCaptor.forClass(Appointment.class);
        verify(appointmentRepository).save(captor.capture());
        assertThat(captor.getValue().getIdDoctor()).isEqualTo(idDoctor);
        assertThat(captor.getValue().getIdPatient()).isEqualTo(idPatient);
        assertThat(appointmentId).isNotNull();
        verifyNoInteractions(clinicalHistoryPort);
    }

    @Test
    void shouldRegisterClinicalHistoryWhenCheckupIsProvided() {
        UUID idDoctor = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientSchedulingContext context = manualContext();
        when(manualPatientResolutionStrategy.resolve(context))
                .thenReturn(new ResolvedPatient(idPatient, patientInfo()));
        stubSaveAssigningId();
        when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn("Dra. Prueba");

        UUID appointmentId = useCase.register(idDoctor, context, SpecialtyCode.FISIOTERAPIA, "Control general");

        verify(clinicalHistoryPort).registerClinicalHistory(
                appointmentId, idPatient, "Dra. Prueba", "Control general", LocalDate.now());
    }

    @Test
    void shouldDelegatePatientResolutionToManualStrategy() {
        UUID idDoctor = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientSchedulingContext context = manualContext();
        when(manualPatientResolutionStrategy.resolve(context))
                .thenReturn(new ResolvedPatient(idPatient, patientInfo()));
        stubSaveAssigningId();

        useCase.register(idDoctor, context, SpecialtyCode.FISIOTERAPIA, null);

        verify(manualPatientResolutionStrategy).resolve(context);
    }
}
