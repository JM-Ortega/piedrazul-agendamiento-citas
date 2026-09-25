package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableDatesAndSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.BusySlotService;
import co.edu.unicauca.piedrazul.backend.appointment.exception.NoAvailableDoctorsException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.DoctorsAvailability;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.ScheduleAvailability;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.shared.enums.Workday;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentExternalServiceImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private DoctorConfigConsultPort doctorConfigConsultPort;
    @Mock
    private GetAvailableDatesAndSlotsUseCase getAvailableDatesAndSlotsUseCase;
    @Mock
    private IsNewPatientUseCase isNewPatientUseCase;
    @Mock
    private PatientConsultPort patientConsultPort;

    private AppointmentExternalServiceImpl service;

    @BeforeEach
    void setUp() {
        // BusySlotService es lógica de dominio pura sin dependencias externas
        service = new AppointmentExternalServiceImpl(
                appointmentRepository, doctorConfigConsultPort, getAvailableDatesAndSlotsUseCase,
                isNewPatientUseCase, patientConsultPort, new BusySlotService());
    }

    @Nested
    class FindByDoctorAndDateTests {

        @Test
        void shouldReturnSummariesFilteredByStateWhenStateProvided() {
            UUID idDoctor = UUID.randomUUID();
            UUID idPatient = UUID.randomUUID();
            LocalDate date = LocalDate.now().plusDays(1);
            Appointment appointment = buildAppointment(idDoctor, idPatient, date, LocalTime.of(9, 0),
                    AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);

            when(appointmentRepository.findByDoctorIdAndDateAndState(idDoctor, date, "AGENDADA"))
                    .thenReturn(List.of(appointment));
            when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn("Dra. Prueba");
            when(patientConsultPort.findByIds(Set.of(idPatient)))
                    .thenReturn(Map.of(idPatient, buildPatientInfo()));

            List<AppointmentSummary> result = service.findByDoctorAndDate(idDoctor, date, "AGENDADA");

            assertThat(result).hasSize(1);
            AppointmentSummary summary = result.getFirst();
            assertThat(summary.idDoctor()).isEqualTo(idDoctor);
            assertThat(summary.doctorName()).isEqualTo("Dra. Prueba");
            assertThat(summary.idPatient()).isEqualTo(idPatient);
            assertThat(summary.patientFullName()).isEqualTo("Carlos Gomez");
            assertThat(summary.specialty()).isEqualTo("FISIOTERAPIA");
            assertThat(summary.state()).isEqualTo("AGENDADA");
            verify(appointmentRepository, never()).findByDoctorIdAndDate(any(), any());
        }

        @Test
        void shouldReturnAllAppointmentsWhenStateIsNull() {
            UUID idDoctor = UUID.randomUUID();
            UUID idPatient = UUID.randomUUID();
            LocalDate date = LocalDate.now().plusDays(1);
            Appointment appointment = buildAppointment(idDoctor, idPatient, date, LocalTime.of(9, 0),
                    AppointmentState.CANCELADA, SpecialtyCode.FISIOTERAPIA);

            when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of(appointment));
            when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn("Dra. Prueba");
            when(patientConsultPort.findByIds(Set.of(idPatient)))
                    .thenReturn(Map.of(idPatient, buildPatientInfo()));

            List<AppointmentSummary> result = service.findByDoctorAndDate(idDoctor, date, null);

            assertThat(result).hasSize(1);
            verify(appointmentRepository, never())
                    .findByDoctorIdAndDateAndState(any(), any(), any());
        }
    }

    @Nested
    class FindAllByDateTests {

        @Test
        void shouldReturnOnlyAgendadaAppointmentsMappedToSchedulerSummary() {
            LocalDate date = LocalDate.now().plusDays(1);
            UUID idDoctor = UUID.randomUUID();
            UUID idPatientAgendada = UUID.randomUUID();
            UUID idPatientCancelada = UUID.randomUUID();

            Appointment agendada = buildAppointment(idDoctor, idPatientAgendada, date, LocalTime.of(9, 0),
                    AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);
            Appointment cancelada = buildAppointment(idDoctor, idPatientCancelada, date, LocalTime.of(10, 0),
                    AppointmentState.CANCELADA, SpecialtyCode.FISIOTERAPIA);

            when(appointmentRepository.findAllByDate(date)).thenReturn(List.of(agendada, cancelada));
            when(doctorConfigConsultPort.getDoctorInfoByIds(List.of(idDoctor)))
                    .thenReturn(List.of(new DoctorResponse(List.of("FISIOTERAPIA"), idDoctor, "Dra. Prueba",
                            LocalDate.now().plusMonths(1), LocalDate.now(), 4, List.of(1))));
            when(patientConsultPort.findByIds(Set.of(idPatientAgendada)))
                    .thenReturn(Map.of(idPatientAgendada, buildPatientInfo()));

            List<SchedulerAppointmentSummary> result = service.findAllByDate(date);

            assertThat(result).containsExactly(
                    new SchedulerAppointmentSummary("Dra. Prueba", "Carlos Gomez", LocalTime.of(9, 0)));
        }
    }

    @Nested
    class HasAvailableSlotsTests {

        @Test
        void shouldReturnFalseWhenDateIsBeforeToday() {
            boolean result = service.hasAvailableSlots(LocalDate.now().minusDays(1));

            assertThat(result).isFalse();
            verifyNoInteractions(doctorConfigConsultPort);
        }

        @Test
        void shouldReturnFalseWhenNoActiveDoctors() {
            when(doctorConfigConsultPort.getActiveDoctorIds()).thenReturn(List.of());

            boolean result = service.hasAvailableSlots(LocalDate.now().plusDays(1));

            assertThat(result).isFalse();
        }

        @Test
        void shouldReturnTrueWhenAnActiveDoctorHasTheDateAvailable() {
            UUID idDoctor = UUID.randomUUID();
            LocalDate date = LocalDate.now().plusDays(1);

            when(doctorConfigConsultPort.getActiveDoctorIds()).thenReturn(List.of(idDoctor));
            when(getAvailableDatesAndSlotsUseCase.getAvailableDates(idDoctor)).thenReturn(List.of(date));

            boolean result = service.hasAvailableSlots(date);

            assertThat(result).isTrue();
        }

        @Test
        void shouldReturnFalseWhenNoActiveDoctorHasTheDateAvailable() {
            UUID idDoctor = UUID.randomUUID();
            LocalDate date = LocalDate.now().plusDays(1);

            when(doctorConfigConsultPort.getActiveDoctorIds()).thenReturn(List.of(idDoctor));
            when(getAvailableDatesAndSlotsUseCase.getAvailableDates(idDoctor))
                    .thenReturn(List.of(date.plusDays(1)));

            boolean result = service.hasAvailableSlots(date);

            assertThat(result).isFalse();
        }
    }

    @Nested
    class HasScheduledAppointmentsTests {

        @Test
        void shouldReturnTrueWhenDoctorHasAgendadaAppointments() {
            UUID idDoctor = UUID.randomUUID();
            when(appointmentRepository.findByDoctorIdAndState(idDoctor, "AGENDADA"))
                    .thenReturn(List.of(buildAppointment(idDoctor, UUID.randomUUID(), LocalDate.now().plusDays(1),
                            LocalTime.of(9, 0), AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA)));

            assertThat(service.hasScheduledAppointments(idDoctor)).isTrue();
        }

        @Test
        void shouldReturnFalseWhenDoctorHasNoAgendadaAppointments() {
            UUID idDoctor = UUID.randomUUID();
            when(appointmentRepository.findByDoctorIdAndState(idDoctor, "AGENDADA")).thenReturn(List.of());

            assertThat(service.hasScheduledAppointments(idDoctor)).isFalse();
        }
    }

    @Nested
    class IsNewPatientTests {

        @Test
        void shouldDelegateToIsNewPatientUseCase() {
            UUID idPatient = UUID.randomUUID();
            when(isNewPatientUseCase.isNewPatient(idPatient)).thenReturn(true);

            assertThat(service.isNewPatient(idPatient)).isTrue();
        }
    }

    @Nested
    class GetAvailableDatesAndSlotsDelegationTests {

        @Test
        void getAvailableDatesShouldDelegateToUseCase() {
            UUID idDoctor = UUID.randomUUID();
            List<LocalDate> dates = List.of(LocalDate.now().plusDays(1));
            when(getAvailableDatesAndSlotsUseCase.getAvailableDates(idDoctor)).thenReturn(dates);

            assertThat(service.getAvailableDates(idDoctor)).isEqualTo(dates);
        }

        @Test
        void getAvailableSlotsShouldDelegateToUseCase() {
            UUID idDoctor = UUID.randomUUID();
            LocalDate date = LocalDate.now().plusDays(1);
            List<LocalTime> slots = List.of(LocalTime.of(8, 0));
            when(getAvailableDatesAndSlotsUseCase.getAvailableSlots(idDoctor, date)).thenReturn(slots);

            assertThat(service.getAvailableSlots(idDoctor, date)).isEqualTo(slots);
        }
    }

    @Nested
    class CalculateDoctorsAvailabilityTests {

        @Test
        void shouldIncludeDoctorWhenAFreeSlotExistsWithinBookingWindow() {
            UUID idDoctor = UUID.randomUUID();
            DoctorsAvailability doctor = new DoctorsAvailability(
                    idDoctor, 1, 30, allWeekdaysSchedule(LocalTime.of(8, 0), LocalTime.of(9, 0)));

            when(appointmentRepository.findByDoctorIdAndDate(eq(idDoctor), any())).thenReturn(List.of());

            Set<UUID> result = service.calculateDoctorsAvailability(List.of(doctor));

            assertThat(result).containsExactly(idDoctor);
        }

        @Test
        void shouldThrowWhenEveryDoctorHasAllSlotsOccupiedInTheWindow() {
            UUID idDoctor = UUID.randomUUID();
            // Franja de un único instante (8:00-8:00) => un único slot posible por día, ocupado siempre.
            // Con un rango más amplio el bucle de hasAvailableSlotInSchedule también evalúa
            // schedule.endTime() como slot válido, así que una franja más ancha dejaría un
            // hueco libre exactamente al cierre.
            DoctorsAvailability doctor = new DoctorsAvailability(
                    idDoctor, 1, 30, allWeekdaysSchedule(LocalTime.of(8, 0), LocalTime.of(8, 0)));

            Appointment busyEveryDay = buildAppointment(idDoctor, UUID.randomUUID(), LocalDate.now(),
                    LocalTime.of(8, 0), AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);
            when(appointmentRepository.findByDoctorIdAndDate(eq(idDoctor), any()))
                    .thenReturn(List.of(busyEveryDay));

            assertThatThrownBy(() -> service.calculateDoctorsAvailability(List.of(doctor)))
                    .isInstanceOf(NoAvailableDoctorsException.class);
        }

        @Test
        void shouldReturnOnlyTheAvailableDoctorsAmongSeveral() {
            UUID availableDoctorId = UUID.randomUUID();
            UUID busyDoctorId = UUID.randomUUID();

            DoctorsAvailability availableDoctor = new DoctorsAvailability(
                    availableDoctorId, 1, 30, allWeekdaysSchedule(LocalTime.of(8, 0), LocalTime.of(9, 0)));
            DoctorsAvailability busyDoctor = new DoctorsAvailability(
                    busyDoctorId, 1, 30, allWeekdaysSchedule(LocalTime.of(8, 0), LocalTime.of(8, 0)));

            Appointment busyEveryDay = buildAppointment(busyDoctorId, UUID.randomUUID(), LocalDate.now(),
                    LocalTime.of(8, 0), AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);

            when(appointmentRepository.findByDoctorIdAndDate(eq(availableDoctorId), any())).thenReturn(List.of());
            when(appointmentRepository.findByDoctorIdAndDate(eq(busyDoctorId), any()))
                    .thenReturn(List.of(busyEveryDay));

            Set<UUID> result = service.calculateDoctorsAvailability(List.of(availableDoctor, busyDoctor));

            assertThat(result).containsExactly(availableDoctorId);
        }
    }

    // ─────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────

    private Appointment buildAppointment(UUID idDoctor, UUID idPatient, LocalDate date, LocalTime time,
            AppointmentState state, SpecialtyCode specialty) {
        return Appointment.reconstruct(
                UUID.randomUUID(), idDoctor, idPatient, specialty, state, date,
                new AppointmentTime(time), SchedulingOrigin.MANUAL);
    }

    private PatientInfo buildPatientInfo() {
        return PatientInfo.of(
                DocumentType.CEDULA, "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "carlos@correo.com", null);
    }

    /**
     * Franja de lunes a viernes con el mismo horario — evita que el test dependa
     * de qué día de la semana se ejecute realmente.
     */
    private Set<ScheduleAvailability> allWeekdaysSchedule(LocalTime start, LocalTime end) {
        return Set.of(
                new ScheduleAvailability(start, end, Workday.LUNES),
                new ScheduleAvailability(start, end, Workday.MARTES),
                new ScheduleAvailability(start, end, Workday.MIERCOLES),
                new ScheduleAvailability(start, end, Workday.JUEVES),
                new ScheduleAvailability(start, end, Workday.VIERNES)
        );
    }
}
