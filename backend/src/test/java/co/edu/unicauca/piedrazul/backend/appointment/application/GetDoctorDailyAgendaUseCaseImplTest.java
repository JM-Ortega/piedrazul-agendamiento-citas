package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PageQuery;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PagedResult;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetDoctorDailyAgendaUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    private GetDoctorDailyAgendaUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetDoctorDailyAgendaUseCaseImpl(appointmentRepository);
    }

    @Test
    void executeShouldDelegateToRepository() {
        UUID idDoctor = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        PageQuery pageQuery = new PageQuery(0, 10, "startTime", true);
        PagedResult<Appointment> expected = new PagedResult<>(List.of(), 0, 10, 0, 0);
        when(appointmentRepository.ListDoctorDailyAgenda(idDoctor, date, pageQuery)).thenReturn(expected);

        PagedResult<Appointment> result = useCase.execute(idDoctor, date, pageQuery);

        assertThat(result).isEqualTo(expected);
    }
}
