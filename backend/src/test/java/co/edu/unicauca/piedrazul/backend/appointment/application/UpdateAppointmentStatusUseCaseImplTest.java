package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.ClinicalHistoryPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateAppointmentStatusUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private DoctorConfigConsultPort doctorConfigConsultPort;
    @Mock
    private ClinicalHistoryPort clinicalHistoryPort;

    private UpdateAppointmentStatusUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateAppointmentStatusUseCaseImpl(appointmentRepository, doctorConfigConsultPort,
                clinicalHistoryPort);
    }

    private Appointment buildScheduledAppointment(UUID idDoctor, UUID idPatient) {
        return Appointment.reconstruct(
                UUID.randomUUID(), idDoctor, idPatient, SpecialtyCode.FISIOTERAPIA,
                AppointmentState.AGENDADA, LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0)), SchedulingOrigin.MANUAL);
    }

    @Nested
    class MarkAsAttendedTests {

        @Test
        void shouldChangeStateToAtendidaAndSkipClinicalHistoryWhenDescriptionIsBlank() {
            UUID appointmentId = UUID.randomUUID();
            Appointment appointment = buildScheduledAppointment(UUID.randomUUID(), UUID.randomUUID());
            when(appointmentRepository.findById(appointmentId)).thenReturn(appointment);

            useCase.markAsAttended(appointmentId, "   ");

            assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.ATENDIDA);
            verify(appointmentRepository).save(appointment);
            verifyNoInteractions(clinicalHistoryPort);
        }

        @Test
        void shouldRegisterClinicalHistoryWhenDescriptionIsProvided() {
            UUID appointmentId = UUID.randomUUID();
            UUID idDoctor = UUID.randomUUID();
            UUID idPatient = UUID.randomUUID();
            Appointment appointment = buildScheduledAppointment(idDoctor, idPatient);
            when(appointmentRepository.findById(appointmentId)).thenReturn(appointment);
            when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn("Dra. Prueba");

            useCase.markAsAttended(appointmentId, "Paciente estable");

            verify(clinicalHistoryPort).registerClinicalHistory(
                    appointment.getIdAppointment(), idPatient, "Dra. Prueba", "Paciente estable", LocalDate.now());
        }

        @Test
        void shouldPropagateIllegalStateExceptionWhenAppointmentIsNotAgendada() {
            UUID appointmentId = UUID.randomUUID();
            Appointment appointment = Appointment.reconstruct(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SpecialtyCode.FISIOTERAPIA,
                    AppointmentState.CANCELADA, LocalDate.now().plusDays(1),
                    new AppointmentTime(LocalTime.of(9, 0)), SchedulingOrigin.MANUAL);
            when(appointmentRepository.findById(appointmentId)).thenReturn(appointment);

            assertThatThrownBy(() -> useCase.markAsAttended(appointmentId, null))
                    .isInstanceOf(IllegalStateException.class);

            verifyNoInteractions(clinicalHistoryPort);
        }
    }

    @Nested
    class MarkAsUnassistedTests {

        @Test
        void shouldChangeStateToNoAsistioAndNeverTouchClinicalHistory() {
            UUID appointmentId = UUID.randomUUID();
            Appointment appointment = buildScheduledAppointment(UUID.randomUUID(), UUID.randomUUID());
            when(appointmentRepository.findById(appointmentId)).thenReturn(appointment);

            useCase.markAsUnassisted(appointmentId);

            assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.NO_ASISTIO);
            verify(appointmentRepository).save(appointment);
            verifyNoInteractions(clinicalHistoryPort, doctorConfigConsultPort);
        }
    }
}
