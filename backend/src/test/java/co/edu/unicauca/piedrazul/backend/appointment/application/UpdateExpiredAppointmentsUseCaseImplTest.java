package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateExpiredAppointmentsUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    private UpdateExpiredAppointmentsUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new UpdateExpiredAppointmentsUseCaseImpl(appointmentRepository);
    }

    private Appointment buildScheduledAppointment(LocalDate date) {
        return Appointment.reconstruct(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SpecialtyCode.FISIOTERAPIA,
                AppointmentState.AGENDADA, date, new AppointmentTime(LocalTime.of(9, 0)), SchedulingOrigin.MANUAL);
    }

    @Test
    void shouldNotTouchRepositoryWhenThereAreNoExpiredAppointments() {
        when(appointmentRepository.findScheduledAppointmentsBefore(LocalDate.now())).thenReturn(List.of());

        useCase.updateExpiredAppointments();

        verify(appointmentRepository, never()).save(any());
    }

    @Test
    void shouldMarkEveryExpiredAppointmentAsNoAsistioAndSaveIt() {
        Appointment first = buildScheduledAppointment(LocalDate.now().minusDays(2));
        Appointment second = buildScheduledAppointment(LocalDate.now().minusDays(1));
        when(appointmentRepository.findScheduledAppointmentsBefore(LocalDate.now()))
                .thenReturn(List.of(first, second));

        useCase.updateExpiredAppointments();

        assertThat(first.getAppointmentState()).isEqualTo(AppointmentState.NO_ASISTIO);
        assertThat(second.getAppointmentState()).isEqualTo(AppointmentState.NO_ASISTIO);
        verify(appointmentRepository).save(first);
        verify(appointmentRepository).save(second);
    }
}
