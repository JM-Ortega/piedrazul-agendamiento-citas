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
        List<Appointment> expected = List.of(buildAppointment(idDoctor, idPatient, date, ));

        when(appointmentRepository.findByDoctorIdAndPatientIdAndDate(idDoctor, idPatient, date))
                .thenReturn(expected);

        List<Appointment> result = useCase.listBy(idDoctor, idPatient, date);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).findByDoctorIdAndPatientIdAndDate(idDoctor, idPatient, date);
    }

    // ─────────────────────────────────────────────
    // Caso 2 — médico y paciente sin fecha
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByDoctorIdAndPatientIdWhenOnlyDoctorAndPatient() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        List<Appointment> expected = List.of(buildAppointment(idDoctor, idPatient, LocalDate.now()));

        when(appointmentRepository.findByDoctorIdAndPatientId(idDoctor, idPatient))
                .thenReturn(expected);

        List<Appointment> result = useCase.listBy(idDoctor, idPatient, null, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).findByDoctorIdAndPatientId(idDoctor, idPatient);
    }

    // ─────────────────────────────────────────────
    // Caso 3 — médico y fecha sin paciente
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByDoctorIdAndDateWhenOnlyDoctorAndDate() {
        UUID idDoctor = UUID.randomUUID();
        LocalDate date = LocalDate.now();
        List<Appointment> expected = List.of(
                buildAppointment(idDoctor, UUID.randomUUID(), date)
        );

        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date))
                .thenReturn(expected);

        List<Appointment> result = useCase.listBy(idDoctor, null, date);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).findByDoctorIdAndDate(idDoctor, date);
    }

    // ─────────────────────────────────────────────
    // Caso 4 — paciente y fecha sin médico
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByPatientIdAndDateWhenOnlyPatientAndDate() {
        UUID idPatient = UUID.randomUUID();
        LocalDate date = LocalDate.now();
        List<Appointment> expected = List.of(
                buildAppointment(UUID.randomUUID(), idPatient, date)
        );

        when(appointmentRepository.findByPatientIdAndDate(idPatient, date))
                .thenReturn(expected);

        List<Appointment> result = useCase.listBy(null, idPatient, date);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).findByPatientIdAndDate(idPatient, date);
    }

    // ─────────────────────────────────────────────
    // Caso 5 — solo médico
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByDoctorIdWhenOnlyDoctorPresent() {
        UUID idDoctor = UUID.randomUUID();
        List<Appointment> expected = List.of(
                buildAppointment(idDoctor, UUID.randomUUID(), LocalDate.now())
        );

        when(appointmentRepository.findByDoctorId(idDoctor)).thenReturn(expected);

        List<Appointment> result = useCase.listBy(idDoctor, null, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).findByDoctorId(idDoctor);
    }

    // ─────────────────────────────────────────────
    // Caso 6 — solo paciente
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByPatientIdWhenOnlyPatientPresent() {
        UUID idPatient = UUID.randomUUID();
        List<Appointment> expected = List.of(
                buildAppointment(UUID.randomUUID(), idPatient, LocalDate.now())
        );

        when(appointmentRepository.findByPatientId(idPatient)).thenReturn(expected);

        List<Appointment> result = useCase.listBy(null, idPatient, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).findByPatientId(idPatient);
    }

    // ─────────────────────────────────────────────
    // Caso 7 — solo fecha
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindByDateWhenOnlyDatePresent() {
        LocalDate date = LocalDate.now();
        List<Appointment> expected = List.of(
                buildAppointment(UUID.randomUUID(), UUID.randomUUID(), date)
        );

        when(appointmentRepository.findByDate(date)).thenReturn(expected);

        List<Appointment> result = useCase.listBy(null, null, date);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).findByDate(date);
    }

    // ─────────────────────────────────────────────
    // Caso default — sin filtros
    // ─────────────────────────────────────────────

    @Test
    void listByShouldCallFindAllWhenNoFiltersPresent() {
        List<Appointment> expected = List.of(
                buildAppointment(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now())
        );

        when(appointmentRepository.findAll()).thenReturn(expected);

        List<Appointment> result = useCase.listBy(null, null, null);

        assertThat(result).isEqualTo(expected);
        verify(appointmentRepository).findAll();
    }

    // ─────────────────────────────────────────────
    // Fixture
    // ─────────────────────────────────────────────

    private Appointment buildAppointment(UUID idDoctor, UUID idPatient, LocalDate date) {
        return Appointment.reconstruct(
                UUID.randomUUID(),
                idDoctor,
                idPatient,
                SpecialtyCode.FISIOTERAPIA,
                AppointmentState.AGENDADA,
                date,
                new AppointmentTime(LocalTime.of(9, 0)),
                SchedulingOrigin.AUTONOMO
        );
    }
}