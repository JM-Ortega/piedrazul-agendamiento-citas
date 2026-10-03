package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentAccessDeniedException;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Un paciente solo cancela sus propias citas; el agendador (sin paciente) cancela cualquiera. */
class CancelAppointmentUseCaseImplTest {

    private static final UUID APPOINTMENT_ID = UUID.fromString("55555555-0000-0000-0000-000000000005");
    private static final UUID OWNER_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID OTHER_PATIENT_ID = UUID.fromString("22222222-0000-0000-0000-000000000002");

    private AppointmentRepository repository;
    private CancelAppointmentUseCaseImpl useCase;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        repository = mock(AppointmentRepository.class);
        useCase = new CancelAppointmentUseCaseImpl(repository);
        appointment = Appointment.reconstruct(
                APPOINTMENT_ID,
                UUID.fromString("33333333-0000-0000-0000-000000000003"),
                OWNER_ID,
                SpecialtyCode.QUIROPRAXIA,
                AppointmentState.AGENDADA,
                LocalDate.now().plusDays(3),
                new AppointmentTime(LocalTime.of(9, 0)),
                SchedulingOrigin.AUTONOMO);
        when(repository.findById(APPOINTMENT_ID)).thenReturn(appointment);
    }

    @Test
    void aPatientCannotCancelAnotherPatientsAppointment() {
        assertThatThrownBy(() -> useCase.cancel(APPOINTMENT_ID, OTHER_PATIENT_ID))
                .isInstanceOf(AppointmentAccessDeniedException.class);

        verify(repository, never()).save(any());
        assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
    }

    @Test
    void aPatientCanCancelTheirOwnAppointment() {
        useCase.cancel(APPOINTMENT_ID, OWNER_ID);

        verify(repository).save(appointment);
        assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
    }

    @Test
    void theSchedulerCancelsWithoutAPatientAndCanCancelAnyAppointment() {
        useCase.cancel(APPOINTMENT_ID, null);

        verify(repository).save(appointment);
        assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
    }
}
