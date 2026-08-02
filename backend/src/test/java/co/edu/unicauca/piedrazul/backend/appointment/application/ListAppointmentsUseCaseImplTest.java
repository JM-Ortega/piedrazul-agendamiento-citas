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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListAppointmentsUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    private ListAppointmentsUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new ListAppointmentsUseCaseImpl(appointmentRepository);
    }

    // ─────────────────────────────────────────────
    // Caso 1 — los 3 filtros presentes
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByDoctorIdAndPatientIdAndDateWhenAllFiltersPresent() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        LocalDate date = LocalDate.now();
        AppointmentState state = AppointmentState.AGENDADA;
        List<Appointment> expected = List.of(buildAppointment(idDoctor, idPatient, date, state));

        when(appointmentRepository.listBy(idDoctor, idPatient, date, state))
                .thenReturn(expected);

        List<Appointment> result = useCase.listBy(idDoctor, idPatient, date, state);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).listBy(idDoctor, idPatient, date, state);
    }

    // ─────────────────────────────────────────────
    // Caso 2 — médico y paciente sin fecha
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByDoctorIdAndPatientIdWhenOnlyDoctorAndPatient() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentState state = AppointmentState.AGENDADA;
        List<Appointment> expected = List.of(buildAppointment(idDoctor, idPatient, LocalDate.now(), state));

        when(appointmentRepository.listBy(idDoctor, idPatient, null, null))
                .thenReturn(expected);

        List<Appointment> result = useCase.listBy(idDoctor, idPatient, null, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).listBy(idDoctor, idPatient, null, null);
    }

    // ─────────────────────────────────────────────
    // Caso 3 — médico y fecha sin paciente
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByDoctorIdAndDateWhenOnlyDoctorAndDate() {
        UUID idDoctor = UUID.randomUUID();
        LocalDate date = LocalDate.now();
        List<Appointment> expected = List.of(
                buildAppointment(idDoctor, UUID.randomUUID(), date, AppointmentState.AGENDADA)
        );

        when(appointmentRepository.listBy(idDoctor, null, date, null))
                .thenReturn(expected);

        List<Appointment> result = useCase.listBy(idDoctor, null, date, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).listBy(idDoctor, null, date, null);
    }

    // ─────────────────────────────────────────────
    // Caso 4 — paciente y fecha sin médico
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByPatientIdAndDateWhenOnlyPatientAndDate() {
        UUID idPatient = UUID.randomUUID();
        LocalDate date = LocalDate.now();
        List<Appointment> expected = List.of(
                buildAppointment(UUID.randomUUID(), idPatient, date, AppointmentState.AGENDADA)
        );

        when(appointmentRepository.listBy(null, idPatient, date, null))
                .thenReturn(expected);

        List<Appointment> result = useCase.listBy(null, idPatient, date, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).listBy(null, idPatient, date, null);
    }

    // ─────────────────────────────────────────────
    // Caso 5 — solo médico
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByDoctorIdWhenOnlyDoctorPresent() {
        UUID idDoctor = UUID.randomUUID();
        List<Appointment> expected = List.of(
                buildAppointment(idDoctor, UUID.randomUUID(), LocalDate.now(), AppointmentState.AGENDADA)
        );

        when(appointmentRepository.listBy(idDoctor, null, null, null)).thenReturn(expected);

        List<Appointment> result = useCase.listBy(idDoctor, null, null, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).listBy(idDoctor, null, null, null);
    }

    // ─────────────────────────────────────────────
    // Caso 6 — solo paciente
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByPatientIdWhenOnlyPatientPresent() {
        UUID idPatient = UUID.randomUUID();
        List<Appointment> expected = List.of(
                buildAppointment(UUID.randomUUID(), idPatient, LocalDate.now(), AppointmentState.AGENDADA)
        );

        when(appointmentRepository.listBy(null, idPatient, null, null)).thenReturn(expected);

        List<Appointment> result = useCase.listBy(null, idPatient, null, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).listBy(null, idPatient, null, null);
    }

    // ─────────────────────────────────────────────
    // Caso 7 — solo fecha
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByDateWhenOnlyDatePresent() {
        LocalDate date = LocalDate.now();
        List<Appointment> expected = List.of(
                buildAppointment(UUID.randomUUID(), UUID.randomUUID(), date, AppointmentState.AGENDADA)
        );

        when(appointmentRepository.listBy(null, null, date, null)).thenReturn(expected);

        List<Appointment> result = useCase.listBy(null, null, date, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).listBy(null, null, date, null);
    }

    // ─────────────────────────────────────────────
    // Caso default — sin filtros
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindAllWhenNoFiltersPresent() {
        List<Appointment> expected = List.of(
                buildAppointment(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), AppointmentState.AGENDADA)
        );

        when(appointmentRepository.listBy(null, null, null, null)).thenReturn(expected);

        List<Appointment> result = useCase.listBy(null, null, null, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).listBy(null, null, null, null);
    }

    // ─────────────────────────────────────────────
    // Fixture
    // ─────────────────────────────────────────────

    private Appointment buildAppointment(UUID idDoctor, UUID idPatient, LocalDate date, AppointmentState state) {
        return Appointment.reconstruct(
                UUID.randomUUID(),
                idDoctor,
                idPatient,
                SpecialtyCode.FISIOTERAPIA,
                state,
                date,
                new AppointmentTime(LocalTime.of(9, 0)),
                SchedulingOrigin.AUTONOMO
        );
    }
}