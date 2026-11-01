package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentAccessDeniedException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.CancelAppointmentNotAllowedException;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CancelAppointmentUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    private CancelAppointmentUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new CancelAppointmentUseCaseImpl(appointmentRepository);
    }

    private Appointment buildAppointment(UUID idPatient, LocalDate date) {
        return Appointment.reconstruct(
                UUID.randomUUID(), UUID.randomUUID(), idPatient, SpecialtyCode.FISIOTERAPIA,
                AppointmentState.AGENDADA, date, new AppointmentTime(LocalTime.of(9, 0)), SchedulingOrigin.MANUAL);
    }

    @Test
    void shouldCancelWhenOwningPatientCancelsTheirOwnAppointment() {
        UUID appointmentId = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        Appointment appointment = buildAppointment(idPatient, LocalDate.now().plusDays(1));
        when(appointmentRepository.findById(appointmentId)).thenReturn(appointment);

        useCase.cancel(appointmentId, idPatient);

        assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        verify(appointmentRepository).save(appointment);
    }

    @Test
    void shouldCancelWhenPatientIdIsNullBecauseStaffCancels() {
        UUID appointmentId = UUID.randomUUID();
        Appointment appointment = buildAppointment(UUID.randomUUID(), LocalDate.now().plusDays(1));
        when(appointmentRepository.findById(appointmentId)).thenReturn(appointment);

        useCase.cancel(appointmentId, null);

        assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        verify(appointmentRepository).save(appointment);
    }

    @Test
    void shouldThrowWhenPatientIdDoesNotMatchAppointmentOwner() {
        UUID appointmentId = UUID.randomUUID();
        Appointment appointment = buildAppointment(UUID.randomUUID(), LocalDate.now().plusDays(1));
        when(appointmentRepository.findById(appointmentId)).thenReturn(appointment);

        assertThatThrownBy(() -> useCase.cancel(appointmentId, UUID.randomUUID()))
                .isInstanceOf(AppointmentAccessDeniedException.class);

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void shouldThrowWhenAppointmentDateIsInThePast() {
        UUID appointmentId = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        Appointment appointment = buildAppointment(idPatient, LocalDate.now().minusDays(1));
        when(appointmentRepository.findById(appointmentId)).thenReturn(appointment);

        assertThatThrownBy(() -> useCase.cancel(appointmentId, idPatient))
                .isInstanceOf(CancelAppointmentNotAllowedException.class);

        verify(appointmentRepository, never()).save(any());
    }
}
