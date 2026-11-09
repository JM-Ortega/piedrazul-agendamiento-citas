package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PageQuery;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PagedResult;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.DoctorDailyCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyGroupCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.statistics.MonthlyStateCount;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentRepositoryImpl;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integración real contra Postgres para la lógica de {@link AppointmentRepositoryImpl} que no
 * se puede verificar con mocks: el {@code Specification} dinámico de {@code listBy} y el orden
 * de prioridad por estado (CriteriaBuilder CASE WHEN) de {@code ListDoctorDailyAgenda}.
 *
 * {@code appointment.patient_id}/{@code doctor_id} son FK reales hacia {@code patient}/{@code doctor},
 * así que los fixtures crean Person+Patient / Person+Doctor reales en vez de UUIDs sueltos.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AppointmentMapper.class, PersonExternalServiceImp.class, AppointmentRepositoryQueryIT.TestBeans.class})
class AppointmentRepositoryQueryIT extends PostgresIntegrationSupport {

    @TestConfiguration
    static class TestBeans {
        @Bean
        AppointmentRepository appointmentRepository(AppointmentJpaRepository jpaRepository, AppointmentMapper mapper) {
            return new AppointmentRepositoryImpl(jpaRepository, mapper);
        }
    }

    @MockitoBean
    private KeycloakUserService keycloakUserService;

    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private PersonExternalService personExternalService;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DoctorRepository doctorRepository;

    private final AtomicLong documentSequence = new AtomicLong(System.nanoTime());

    private String uniqueDocument() {
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    private UUID newPatient() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Pat", "Ient", "3000000000", null, null);
        patientRepository.save(new Patient(person.id(), Sex.FEMENINO, LocalDate.of(1990, 1, 1), null));
        return person.id();
    }

    private UUID newDoctor() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Doc", "Tor", "3000000001", null, null);
        doctorRepository.save(new Doctor(person.id(), LocalDate.now(), LocalDate.now().plusYears(1), 4, true, 30));
        return person.id();
    }

    private Appointment save(UUID idDoctor, UUID idPatient, LocalDate date, LocalTime time, AppointmentState state,
            SpecialtyCode specialty) {
        Appointment appointment = Appointment.reconstruct(
                null, idDoctor, idPatient, specialty, state, date, new AppointmentTime(time), SchedulingOrigin.MANUAL);
        return appointmentRepository.save(appointment);
    }

    @Nested
    class ListByTests {

        @Test
        void shouldFilterByDoctorOnly() {
            UUID doctorA = newDoctor();
            UUID doctorB = newDoctor();
            LocalDate date = LocalDate.now().plusDays(1);
            save(doctorA, newPatient(), date, LocalTime.of(8, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);
            save(doctorB, newPatient(), date, LocalTime.of(9, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);

            PagedResult<Appointment> result = appointmentRepository.listBy(
                    doctorA, null, null, null, new PageQuery(0, 10, "date", true));

            assertThat(result.content()).hasSize(1);
            assertThat(result.content().getFirst().getIdDoctor()).isEqualTo(doctorA);
            assertThat(result.totalElements()).isEqualTo(1);
        }

        @Test
        void shouldFilterByPatientDateAndStateCombined() {
            UUID doctor = newDoctor();
            UUID patient = newPatient();
            LocalDate date = LocalDate.now().plusDays(1);
            Appointment target = save(doctor, patient, date, LocalTime.of(8, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);
            // mismo paciente, mismo día, pero CANCELADA: no debe calzar con el filtro de estado
            save(doctor, patient, date, LocalTime.of(9, 0), AppointmentState.CANCELADA, SpecialtyCode.FISIOTERAPIA);
            // mismo paciente y estado, pero otro día: no debe calzar con el filtro de fecha
            save(doctor, patient, date.plusDays(1), LocalTime.of(8, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);

            PagedResult<Appointment> result = appointmentRepository.listBy(
                    null, patient, date, AppointmentState.AGENDADA, new PageQuery(0, 10, "date", true));

            assertThat(result.content()).extracting(Appointment::getIdAppointment)
                    .containsExactly(target.getIdAppointment());
        }

        @Test
        void shouldReturnEmptyPageWhenNoAppointmentMatchesFilters() {
            PagedResult<Appointment> result = appointmentRepository.listBy(
                    UUID.randomUUID(), null, null, null, new PageQuery(0, 10, "date", true));

            assertThat(result.content()).isEmpty();
            assertThat(result.totalElements()).isZero();
        }

        @Test
        void shouldApplyDeterministicSecondarySortByStartTimeWhenSortingByDate() {
            UUID doctor = newDoctor();
            LocalDate date = LocalDate.now().plusDays(1);
            // Insertadas fuera de orden para probar que el sort secundario por startTime las reordena
            Appointment later = save(doctor, newPatient(), date, LocalTime.of(10, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);
            Appointment earlier = save(doctor, newPatient(), date, LocalTime.of(8, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);

            PagedResult<Appointment> result = appointmentRepository.listBy(
                    doctor, null, null, null, new PageQuery(0, 10, "date", true));

            assertThat(result.content()).extracting(Appointment::getIdAppointment)
                    .containsExactly(earlier.getIdAppointment(), later.getIdAppointment());
        }

        @Test
        void shouldRespectPageSizeAndReportTotals() {
            UUID doctor = newDoctor();
            LocalDate date = LocalDate.now().plusDays(1);
            for (int i = 0; i < 3; i++) {
                save(doctor, newPatient(), date, LocalTime.of(8 + i, 0), AppointmentState.AGENDADA,
                        SpecialtyCode.FISIOTERAPIA);
            }

            PagedResult<Appointment> result = appointmentRepository.listBy(
                    doctor, null, null, null, new PageQuery(0, 2, "date", true));

            assertThat(result.content()).hasSize(2);
            assertThat(result.totalElements()).isEqualTo(3);
            assertThat(result.totalPages()).isEqualTo(2);
        }
    }

    @Nested
    class ListDoctorDailyAgendaTests {

        @Test
        void shouldOrderByStatePriorityThenByStartTime() {
            UUID doctor = newDoctor();
            LocalDate date = LocalDate.now().plusDays(1);
            Appointment cancelada = save(doctor, newPatient(), date, LocalTime.of(7, 0),
                    AppointmentState.CANCELADA, SpecialtyCode.FISIOTERAPIA);
            Appointment noAsistio = save(doctor, newPatient(), date, LocalTime.of(7, 30),
                    AppointmentState.NO_ASISTIO, SpecialtyCode.FISIOTERAPIA);
            Appointment atendidaTarde = save(doctor, newPatient(), date, LocalTime.of(11, 0),
                    AppointmentState.ATENDIDA, SpecialtyCode.FISIOTERAPIA);
            Appointment agendadaTarde = save(doctor, newPatient(), date, LocalTime.of(10, 0),
                    AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);
            Appointment agendadaTemprano = save(doctor, newPatient(), date, LocalTime.of(8, 0),
                    AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);

            PagedResult<Appointment> result = appointmentRepository.ListDoctorDailyAgenda(
                    doctor, date, new PageQuery(0, 10, "date", true));

            // AGENDADA (por startTime) < ATENDIDA < NO_ASISTIO < CANCELADA
            assertThat(result.content()).extracting(Appointment::getIdAppointment)
                    .containsExactly(
                            agendadaTemprano.getIdAppointment(),
                            agendadaTarde.getIdAppointment(),
                            atendidaTarde.getIdAppointment(),
                            noAsistio.getIdAppointment(),
                            cancelada.getIdAppointment());
        }

        @Test
        void shouldOnlyIncludeAppointmentsForTheGivenDoctorAndDate() {
            UUID doctor = newDoctor();
            LocalDate date = LocalDate.now().plusDays(1);
            Appointment inScope = save(doctor, newPatient(), date, LocalTime.of(8, 0),
                    AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);
            save(doctor, newPatient(), date.plusDays(1), LocalTime.of(8, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);
            save(newDoctor(), newPatient(), date, LocalTime.of(8, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);

            PagedResult<Appointment> result = appointmentRepository.ListDoctorDailyAgenda(
                    doctor, date, new PageQuery(0, 10, "date", true));

            assertThat(result.content()).extracting(Appointment::getIdAppointment)
                    .containsExactly(inScope.getIdAppointment());
        }
    }

    /**
     * Estadísticas mensuales: valida el JPQL con {@code extract(month ...)}, los alias de las
     * proyecciones y el mapeo (UUID / enum como Object en groupKey, mes 0-based).
     *
     * <p>Se usa un año lejano porque otras IT confirman citas relativas a {@code LocalDate.now()} y
     * contaminarían los conteos del año en curso (el rollback de este test no las toca).
     */
    @Nested
    class MonthlyStatisticsTests {

        private static final int YEAR = 2040;
        private static final List<AppointmentState> ALL_STATES = List.of(AppointmentState.values());

        private UUID doctor1;
        private UUID doctor2;

        @BeforeEach
        void seed() {
            doctor1 = newDoctor();
            doctor2 = newDoctor();
            seedAppointment(doctor1, LocalDate.of(YEAR, 1, 15), 8, AppointmentState.ATENDIDA, SpecialtyCode.FISIOTERAPIA);
            seedAppointment(doctor1, LocalDate.of(YEAR, 1, 20), 9, AppointmentState.CANCELADA, SpecialtyCode.FISIOTERAPIA);
            seedAppointment(doctor2, LocalDate.of(YEAR, 1, 31), 10, AppointmentState.ATENDIDA, SpecialtyCode.TERAPIA_NEURAL);
            seedAppointment(doctor1, LocalDate.of(YEAR, 2, 1), 11, AppointmentState.NO_ASISTIO, SpecialtyCode.TERAPIA_NEURAL);
            seedAppointment(doctor2, LocalDate.of(YEAR, 12, 31), 8, AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);
            seedAppointment(doctor2, LocalDate.of(YEAR, 3, 10), 9, AppointmentState.REPROGRAMADA, SpecialtyCode.FISIOTERAPIA);
            // fuera del año: 31-dic del año anterior y 1-ene del siguiente
            seedAppointment(doctor1, LocalDate.of(YEAR - 1, 12, 31), 10, AppointmentState.ATENDIDA, SpecialtyCode.FISIOTERAPIA);
            seedAppointment(doctor2, LocalDate.of(YEAR + 1, 1, 1), 11, AppointmentState.ATENDIDA, SpecialtyCode.TERAPIA_NEURAL);
        }

        private void seedAppointment(UUID doctor, LocalDate date, int hour, AppointmentState state,
                SpecialtyCode specialty) {
            save(doctor, newPatient(), date, LocalTime.of(hour, 0), state, specialty);
        }

        @Test
        void countByMonthAndDoctorShouldGroupAllStatesByZeroBasedMonthAndDoctor() {
            List<MonthlyGroupCount> result = appointmentRepository.countByMonthAndDoctor(YEAR, ALL_STATES);

            assertThat(result).containsExactlyInAnyOrder(
                    new MonthlyGroupCount(0, doctor1.toString(), 2),
                    new MonthlyGroupCount(0, doctor2.toString(), 1),
                    new MonthlyGroupCount(1, doctor1.toString(), 1),
                    new MonthlyGroupCount(2, doctor2.toString(), 1),
                    new MonthlyGroupCount(11, doctor2.toString(), 1));
        }

        @Test
        void countByMonthAndDoctorShouldFilterByStates() {
            List<MonthlyGroupCount> result = appointmentRepository.countByMonthAndDoctor(
                    YEAR, List.of(AppointmentState.ATENDIDA));

            assertThat(result).containsExactlyInAnyOrder(
                    new MonthlyGroupCount(0, doctor1.toString(), 1),
                    new MonthlyGroupCount(0, doctor2.toString(), 1));
        }

        @Test
        void countByMonthAndSpecialtyShouldGroupAllStatesByZeroBasedMonthAndSpecialty() {
            List<MonthlyGroupCount> result = appointmentRepository.countByMonthAndSpecialty(YEAR, ALL_STATES);

            assertThat(result).containsExactlyInAnyOrder(
                    new MonthlyGroupCount(0, "FISIOTERAPIA", 2),
                    new MonthlyGroupCount(0, "TERAPIA_NEURAL", 1),
                    new MonthlyGroupCount(1, "TERAPIA_NEURAL", 1),
                    new MonthlyGroupCount(2, "FISIOTERAPIA", 1),
                    new MonthlyGroupCount(11, "FISIOTERAPIA", 1));
        }

        @Test
        void countByMonthAndSpecialtyShouldFilterByStates() {
            List<MonthlyGroupCount> result = appointmentRepository.countByMonthAndSpecialty(
                    YEAR, List.of(AppointmentState.ATENDIDA));

            assertThat(result).containsExactlyInAnyOrder(
                    new MonthlyGroupCount(0, "FISIOTERAPIA", 1),
                    new MonthlyGroupCount(0, "TERAPIA_NEURAL", 1));
        }

        @Test
        void countByMonthAndStateShouldGroupEveryStateByZeroBasedMonth() {
            List<MonthlyStateCount> result = appointmentRepository.countByMonthAndState(YEAR);

            assertThat(result).containsExactlyInAnyOrder(
                    new MonthlyStateCount(0, AppointmentState.ATENDIDA, 2),
                    new MonthlyStateCount(0, AppointmentState.CANCELADA, 1),
                    new MonthlyStateCount(1, AppointmentState.NO_ASISTIO, 1),
                    new MonthlyStateCount(2, AppointmentState.REPROGRAMADA, 1),
                    new MonthlyStateCount(11, AppointmentState.AGENDADA, 1));
        }

        @Test
        void shouldLeaveAppointmentsOfAdjacentYearsOutOfTheRange() {
            // 31-dic del año anterior cae en diciembre (mes 11) SOLO de ese año, no del consultado
            assertThat(appointmentRepository.countByMonthAndState(YEAR - 1))
                    .containsExactly(new MonthlyStateCount(11, AppointmentState.ATENDIDA, 1));
            // 1-ene del año siguiente cae en enero (mes 0) SOLO de ese año
            assertThat(appointmentRepository.countByMonthAndState(YEAR + 1))
                    .containsExactly(new MonthlyStateCount(0, AppointmentState.ATENDIDA, 1));
            assertThat(appointmentRepository.countByMonthAndDoctor(YEAR - 1, ALL_STATES))
                    .containsExactly(new MonthlyGroupCount(11, doctor1.toString(), 1));
            assertThat(appointmentRepository.countByMonthAndSpecialty(YEAR + 1, ALL_STATES))
                    .containsExactly(new MonthlyGroupCount(0, "TERAPIA_NEURAL", 1));
        }

        @Test
        void shouldReturnNothingForAYearWithoutAppointments() {
            assertThat(appointmentRepository.countByMonthAndState(YEAR + 5)).isEmpty();
            assertThat(appointmentRepository.countByMonthAndDoctor(YEAR + 5, ALL_STATES)).isEmpty();
            assertThat(appointmentRepository.countByMonthAndSpecialty(YEAR + 5, ALL_STATES)).isEmpty();
        }
    }

    /**
     * Conteo diario por doctor excluyendo un estado: valida el JPQL ({@code <> :excludedState},
     * group by) y el mapeo de la proyección con UUID. Fechas lejanas para no chocar con otras IT.
     */
    @Nested
    class DoctorDailyCountsTests {

        private static final LocalDate DAY = LocalDate.of(2040, 3, 15);

        private UUID doctor1;
        private UUID doctor2;
        private UUID doctor3;
        private UUID doctor4;

        @BeforeEach
        void seed() {
            doctor1 = newDoctor();
            doctor2 = newDoctor();
            doctor3 = newDoctor();
            doctor4 = newDoctor();
            seedAppointment(doctor1, DAY, 8, AppointmentState.AGENDADA);
            seedAppointment(doctor1, DAY, 9, AppointmentState.ATENDIDA);
            seedAppointment(doctor1, DAY, 10, AppointmentState.CANCELADA);
            seedAppointment(doctor2, DAY, 8, AppointmentState.CANCELADA);
            seedAppointment(doctor3, DAY, 8, AppointmentState.NO_ASISTIO);
            seedAppointment(doctor3, DAY, 9, AppointmentState.REPROGRAMADA);
            seedAppointment(doctor3, DAY, 10, AppointmentState.ATENDIDA);
            // otras fechas: no deben contarse
            seedAppointment(doctor1, DAY.plusDays(1), 8, AppointmentState.ATENDIDA);
            seedAppointment(doctor4, DAY.minusDays(1), 8, AppointmentState.AGENDADA);
        }

        private void seedAppointment(UUID doctor, LocalDate date, int hour, AppointmentState state) {
            save(doctor, newPatient(), date, LocalTime.of(hour, 0), state, SpecialtyCode.FISIOTERAPIA);
        }

        @Test
        void shouldCountPerDoctorExcludingTheGivenStateAndOmitDoctorsWithoutRemainingAppointments() {
            List<DoctorDailyCount> result =
                    appointmentRepository.countByDateGroupedByDoctorExcludingState(DAY, AppointmentState.CANCELADA);

            // doctor2 (solo una CANCELADA) y doctor4 (otra fecha) no aparecen
            assertThat(result).containsExactlyInAnyOrder(
                    new DoctorDailyCount(doctor1, 2),
                    new DoctorDailyCount(doctor3, 3));
        }

        @Test
        void shouldOnlyCountAppointmentsOfTheRequestedDate() {
            assertThat(appointmentRepository.countByDateGroupedByDoctorExcludingState(
                    DAY.plusDays(1), AppointmentState.CANCELADA))
                    .containsExactly(new DoctorDailyCount(doctor1, 1));
            assertThat(appointmentRepository.countByDateGroupedByDoctorExcludingState(
                    DAY.minusDays(1), AppointmentState.CANCELADA))
                    .containsExactly(new DoctorDailyCount(doctor4, 1));
        }

        @Test
        void shouldReturnNothingForADateWithoutAppointments() {
            assertThat(appointmentRepository.countByDateGroupedByDoctorExcludingState(
                    DAY.plusDays(2), AppointmentState.CANCELADA)).isEmpty();
        }

        @Test
        void shouldApplyTheExcludedStateReceivedAsParameter() {
            // Excluyendo ATENDIDA en vez de CANCELADA: el estado no está fijo en el JPQL
            assertThat(appointmentRepository.countByDateGroupedByDoctorExcludingState(
                    DAY, AppointmentState.ATENDIDA)).containsExactlyInAnyOrder(
                    new DoctorDailyCount(doctor1, 2),
                    new DoctorDailyCount(doctor2, 1),
                    new DoctorDailyCount(doctor3, 2));
        }
    }

    /**
     * Paginación estable con empates: citas con el mismo date/startTime (y a veces el mismo estado)
     * deben aparecer exactamente una vez al recorrer todas las páginas. Sin un último criterio único,
     * Postgres puede ordenar los empates distinto en cada LIMIT/OFFSET (top-N heapsort).
     *
     * <p>Años lejanos (2041-2043) para no chocar con otras IT, que usan fechas relativas a hoy.
     */
    @Nested
    class StablePaginationTests {

        private static final List<String> SORT_FIELDS = List.of("date", "appointmentState", "idDoctor", "idPatient");
        private static final int[] PAGE_SIZES = {3, 5, 7};
        private static final List<AppointmentState> SEEDED_STATES = List.of(
                AppointmentState.ATENDIDA, AppointmentState.CANCELADA, AppointmentState.NO_ASISTIO);

        private List<UUID> patients;

        @BeforeEach
        void seedPatients() {
            patients = List.of(newPatient(), newPatient(), newPatient());
        }

        private Set<UUID> seed(List<UUID> doctors, int total, LocalDate firstDate, int days, List<AppointmentState> states) {
            Set<UUID> ids = new HashSet<>();
            for (int i = 0; i < total; i++) {
                Appointment saved = save(doctors.get(i % doctors.size()), patients.get(i % patients.size()),
                        firstDate.plusDays(i % days), LocalTime.of(8 + (i / 2) % 2, 0),
                        states.get(i % states.size()), SpecialtyCode.FISIOTERAPIA);
                ids.add(saved.getIdAppointment());
            }
            return ids;
        }

        private List<Appointment> walkAllPages(Function<PageQuery, PagedResult<Appointment>> fetch,
                String sortBy, boolean ascending, int size) {
            List<Appointment> all = new ArrayList<>();
            int page = 0;
            PagedResult<Appointment> result;
            do {
                result = fetch.apply(new PageQuery(page++, size, sortBy, ascending));
                all.addAll(result.content());
            } while (page < result.totalPages());
            return all;
        }

        private Object primaryKey(Appointment a, String sortBy) {
            return switch (sortBy) {
                case "appointmentState" -> a.getAppointmentState();
                case "idDoctor" -> a.getIdDoctor();
                case "idPatient" -> a.getIdPatient();
                default -> a.getDate();
            };
        }

        /** Todas las combinaciones sortBy x dirección x tamaño de página; devuelve las violaciones encontradas. */
        private List<String> checkListBy(Set<UUID> expected, UUID idDoctor, LocalDate date) {
            List<String> problems = new ArrayList<>();
            for (String sortBy : SORT_FIELDS) {
                for (boolean ascending : new boolean[]{true, false}) {
                    List<Appointment> fullOrder = appointmentRepository.listBy(
                            idDoctor, null, date, null, new PageQuery(0, expected.size() + 10, sortBy, ascending)).content();
                    for (int size : PAGE_SIZES) {
                        String combo = "sortBy=" + sortBy + " asc=" + ascending + " size=" + size;
                        List<Appointment> walked = walkAllPages(
                                q -> appointmentRepository.listBy(idDoctor, null, date, null, q), sortBy, ascending, size);
                        List<UUID> walkedIds = walked.stream().map(Appointment::getIdAppointment).toList();
                        Set<UUID> distinct = new HashSet<>(walkedIds);
                        if (distinct.size() != walkedIds.size()) {
                            problems.add(combo + ": " + (walkedIds.size() - distinct.size()) + " repetidas");
                        }
                        if (!distinct.equals(expected)) {
                            Set<UUID> missing = new HashSet<>(expected);
                            missing.removeAll(distinct);
                            problems.add(combo + ": faltan " + missing.size());
                        }
                        if (!walkedIds.equals(fullOrder.stream().map(Appointment::getIdAppointment).toList())) {
                            problems.add(combo + ": el orden paginado difiere del orden completo");
                        }
                        problems.addAll(tieBreakProblems(combo, walked, sortBy));
                    }
                }
            }
            return problems;
        }

        /** Entre vecinos con igual (primario, date, startTime) los ids deben ir ASC (orden de bytes del UUID). */
        private List<String> tieBreakProblems(String combo, List<Appointment> ordered, String sortBy) {
            List<String> problems = new ArrayList<>();
            for (int i = 1; i < ordered.size(); i++) {
                Appointment prev = ordered.get(i - 1);
                Appointment cur = ordered.get(i);
                boolean tied = primaryKey(prev, sortBy).equals(primaryKey(cur, sortBy))
                        && prev.getDate().equals(cur.getDate())
                        && prev.getStartTime().getTime().equals(cur.getStartTime().getTime());
                if (tied && prev.getIdAppointment().toString().compareTo(cur.getIdAppointment().toString()) > 0) {
                    problems.add(combo + ": empate sin desempatar por id en la posición " + i);
                    break;
                }
            }
            return problems;
        }

        @ParameterizedTest(name = "listBy de un médico con {0} citas empatadas")
        @ValueSource(ints = {25, 300})
        void listByShouldPaginateWithoutDuplicatesOrGapsForOneDoctor(int total) {
            UUID doctor = newDoctor();
            Set<UUID> expected = seed(List.of(doctor), total, LocalDate.of(2041, 3, 10), 2, SEEDED_STATES);

            assertThat(checkListBy(expected, doctor, null)).isEmpty();
        }

        @ParameterizedTest(name = "listBy sin médico (SCHEDULER) con {0} citas empatadas")
        @ValueSource(ints = {25, 300})
        void listByShouldPaginateWithoutDuplicatesOrGapsAcrossDoctorsInTheSameSlot(int total) {
            List<UUID> doctors = List.of(newDoctor(), newDoctor(), newDoctor(), newDoctor());
            LocalDate date = LocalDate.of(2042, 5, 4);
            Set<UUID> expected = seed(doctors, total, date, 1, SEEDED_STATES);

            assertThat(checkListBy(expected, null, date)).isEmpty();
        }

        @ParameterizedTest(name = "agenda diaria con {0} citas empatadas")
        @ValueSource(ints = {25, 300})
        void dailyAgendaShouldPaginateWithoutDuplicatesOrGaps(int total) {
            UUID doctor = newDoctor();
            LocalDate date = LocalDate.of(2043, 7, 15);
            Set<UUID> expected = seed(List.of(doctor), total, date, 1, List.of(
                    AppointmentState.AGENDADA, AppointmentState.ATENDIDA,
                    AppointmentState.NO_ASISTIO, AppointmentState.CANCELADA));
            Map<AppointmentState, Integer> priority = Map.of(
                    AppointmentState.AGENDADA, 1, AppointmentState.ATENDIDA, 2,
                    AppointmentState.NO_ASISTIO, 3, AppointmentState.CANCELADA, 4);

            List<String> problems = new ArrayList<>();
            List<UUID> fullOrder = appointmentRepository.ListDoctorDailyAgenda(
                    doctor, date, new PageQuery(0, total + 10, "date", true)).content()
                    .stream().map(Appointment::getIdAppointment).toList();
            for (int size : PAGE_SIZES) {
                List<Appointment> walked = walkAllPages(
                        q -> appointmentRepository.ListDoctorDailyAgenda(doctor, date, q), "date", true, size);
                List<UUID> walkedIds = walked.stream().map(Appointment::getIdAppointment).toList();
                Set<UUID> distinct = new HashSet<>(walkedIds);
                if (distinct.size() != walkedIds.size()) {
                    problems.add("size=" + size + ": " + (walkedIds.size() - distinct.size()) + " repetidas");
                }
                if (!distinct.equals(expected)) {
                    problems.add("size=" + size + ": el conjunto paginado no coincide con el esperado");
                }
                if (!walkedIds.equals(fullOrder)) {
                    problems.add("size=" + size + ": el orden paginado difiere del orden completo");
                }
                for (int i = 1; i < walked.size(); i++) {
                    Appointment prev = walked.get(i - 1);
                    Appointment cur = walked.get(i);
                    int byPriority = priority.get(prev.getAppointmentState()).compareTo(priority.get(cur.getAppointmentState()));
                    int byTime = prev.getStartTime().getTime().compareTo(cur.getStartTime().getTime());
                    int byId = prev.getIdAppointment().toString().compareTo(cur.getIdAppointment().toString());
                    if (byPriority > 0 || (byPriority == 0 && byTime > 0) || (byPriority == 0 && byTime == 0 && byId > 0)) {
                        problems.add("size=" + size + ": orden (prioridad, hora, id) roto en la posición " + i);
                        break;
                    }
                }
            }
            assertThat(problems).isEmpty();
        }
    }

    @Nested
    class OtherQueriesTests {

        @Test
        void findByDoctorAndDateBetweenShouldReturnOnlyAppointmentsWithinRange() {
            UUID doctor = newDoctor();
            LocalDate start = LocalDate.now().plusDays(1);
            LocalDate end = start.plusDays(2);
            Appointment inside = save(doctor, newPatient(), start.plusDays(1), LocalTime.of(8, 0),
                    AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);
            save(doctor, newPatient(), end.plusDays(1), LocalTime.of(8, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);

            List<Appointment> result = appointmentRepository.findByDoctorAndDateBetween(doctor, start, end);

            assertThat(result).extracting(Appointment::getIdAppointment).containsExactly(inside.getIdAppointment());
        }

        @Test
        void findByPatientIdShouldReturnAllAppointmentsForThatPatient() {
            UUID patient = newPatient();
            save(newDoctor(), patient, LocalDate.now().plusDays(1), LocalTime.of(8, 0),
                    AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);
            save(newDoctor(), patient, LocalDate.now().plusDays(2), LocalTime.of(9, 0),
                    AppointmentState.CANCELADA, SpecialtyCode.QUIROPRAXIA);
            save(newDoctor(), newPatient(), LocalDate.now().plusDays(1), LocalTime.of(8, 0),
                    AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);

            List<Appointment> result = appointmentRepository.findByPatientId(patient);

            assertThat(result).hasSize(2);
        }

        @Test
        void existsByPatientIdAndStatesShouldReturnTrueWhenAMatchingStateExists() {
            UUID patient = newPatient();
            save(newDoctor(), patient, LocalDate.now().plusDays(1), LocalTime.of(8, 0),
                    AppointmentState.ATENDIDA, SpecialtyCode.FISIOTERAPIA);

            boolean result = appointmentRepository.existsByPatientIdAndStates(
                    patient, EnumSet.of(AppointmentState.ATENDIDA));

            assertThat(result).isTrue();
        }

        @Test
        void existsByPatientIdAndStatesShouldReturnFalseWhenNoStateMatches() {
            UUID patient = newPatient();
            save(newDoctor(), patient, LocalDate.now().plusDays(1), LocalTime.of(8, 0),
                    AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);

            boolean result = appointmentRepository.existsByPatientIdAndStates(
                    patient, EnumSet.of(AppointmentState.ATENDIDA));

            assertThat(result).isFalse();
        }

        @Test
        void countByDateAndStateShouldCountOnlyAppointmentsWithThatState() {
            // countByDateAndState no admite filtrar por doctor/paciente, así que otra IT
            // (p.ej. ManualSchedulingRollbackIT/AppointmentSchedulingHappyPathIT, que no
            // hacen rollback) puede dejar citas reales en la misma fecha "+1 día"; se mide
            // la variación (delta) en vez de un valor absoluto, igual que ya hace
            // ManualSchedulingRollbackIT con patientRepository.count().
            LocalDate date = LocalDate.now().plusDays(1);
            long countBefore = appointmentRepository.countByDateAndState(date, AppointmentState.AGENDADA);

            save(newDoctor(), newPatient(), date, LocalTime.of(8, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);
            save(newDoctor(), newPatient(), date, LocalTime.of(9, 0), AppointmentState.AGENDADA,
                    SpecialtyCode.FISIOTERAPIA);
            save(newDoctor(), newPatient(), date, LocalTime.of(10, 0), AppointmentState.CANCELADA,
                    SpecialtyCode.FISIOTERAPIA);

            long result = appointmentRepository.countByDateAndState(date, AppointmentState.AGENDADA);

            assertThat(result - countBefore).isEqualTo(2);
        }

        /**
         * Documenta el comportamiento actual (no lo corrige): findById no traduce la ausencia
         * a una excepción de dominio, sino a una IllegalArgumentException cruda.
         */
        @Test
        void findByIdShouldThrowIllegalArgumentExceptionWhenAppointmentDoesNotExist() {
            assertThatThrownBy(() -> appointmentRepository.findById(UUID.randomUUID()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        /**
         * Documenta el comportamiento actual: usa Optional.get() sin orElseThrow, así que
         * revienta con NoSuchElementException (no una excepción de dominio) si no existe.
         */
        @Test
        void getPattientIdByAppointmentIdShouldThrowWhenAppointmentDoesNotExist() {
            assertThatThrownBy(() -> appointmentRepository.getPattientIdByAppointmentId(UUID.randomUUID()))
                    .isInstanceOf(java.util.NoSuchElementException.class);
        }
    }
}
