package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentExternalServiceImpl;
import co.edu.unicauca.piedrazul.backend.appointment.application.GetAppointmentStatisticsUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAppointmentStatisticsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsDateException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsStateException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidStatisticsYearException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.DoctorDailyCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyCancellationDataDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyDoctorRowDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlySpecialtyRowDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.MonthlyTotalAppointmentDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.SpecialtyAppointmentsCountDto;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import co.edu.unicauca.piedrazul.backend.doctors.config.DoctorDataInitializer;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientDataInitializer;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end de las estadísticas (AppointmentExternalService → caso de uso → repositorio → Postgres
 * real) con contexto completo, lo que además comprueba el cableado de los beans.
 *
 * <p>Se usa un año lejano porque otras IT confirman citas relativas a {@code LocalDate.now()} y
 * contaminarían los conteos del año en curso. Las citas sembradas se borran en {@code @AfterEach}
 * (este test no es transaccional).
 *
 * <p>{@code @DirtiesContext} cierra el contexto (y su pool Hikari) al terminar: todas las IT comparten un
 * único Postgres y cada contexto cacheado retiene conexiones; sin esto este contexto extra acerca la
 * suite al límite {@code max_connections} ("too many clients already").
 */
@SpringBootTest
@DirtiesContext
@Import(AppointmentStatisticsIT.TestSecurityBeans.class)
class AppointmentStatisticsIT extends PostgresIntegrationSupport {

    @TestConfiguration
    static class TestSecurityBeans {
        /** Evita resolver el issuer real de Keycloak al arrancar (ver AppointmentControllerIT). */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("No se decodifica JWT real en este test");
            };
        }
    }

    @MockitoBean
    private KeycloakUserService keycloakUserService;
    @MockitoBean
    private VerificationCodeSender verificationCodeSender;
    // Siembran datos vía Keycloak real al arrancar; se mockean (ver AppointmentControllerIT).
    @MockitoBean
    private DoctorDataInitializer doctorDataInitializer;
    @MockitoBean
    private PatientDataInitializer patientDataInitializer;

    private static final int YEAR = 2040;

    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private AppointmentExternalService appointmentExternalService;
    @Autowired
    private AppointmentJpaRepository appointmentJpaRepository;
    @Autowired
    private PersonExternalService personExternalService;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private PatientRepository patientRepository;

    private final AtomicLong documentSequence = new AtomicLong(System.nanoTime());
    private final List<UUID> seededAppointmentIds = new ArrayList<>();

    private UUID doctor1;
    private UUID doctor2;

    private String uniqueDocument() {
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    private UUID newDoctor() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Doc", "Tor", "3000000001", null, null);
        doctorRepository.save(new Doctor(person.id(), LocalDate.now(), LocalDate.now().plusYears(1), 4, true, 30));
        return person.id();
    }

    private UUID newPatient() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Pat", "Ient", "3000000000", null, null);
        patientRepository.save(new Patient(person.id(), Sex.FEMENINO, LocalDate.of(1990, 1, 1), null));
        return person.id();
    }

    private void seedAppointment(UUID doctor, LocalDate date, int hour, AppointmentState state,
            SpecialtyCode specialty) {
        AppointmentEntity saved = appointmentJpaRepository.save(new AppointmentEntity(
                null, doctor, newPatient(), specialty, state, date, LocalTime.of(hour, 0), SchedulingOrigin.MANUAL));
        seededAppointmentIds.add(saved.getIdAppointment());
    }

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
        // fuera del año
        seedAppointment(doctor1, LocalDate.of(YEAR - 1, 12, 31), 10, AppointmentState.ATENDIDA, SpecialtyCode.FISIOTERAPIA);
        seedAppointment(doctor2, LocalDate.of(YEAR + 1, 1, 1), 11, AppointmentState.ATENDIDA, SpecialtyCode.TERAPIA_NEURAL);
    }

    @AfterEach
    void cleanUp() {
        appointmentJpaRepository.deleteAllById(seededAppointmentIds);
        seededAppointmentIds.clear();
    }

    /** Valores esperados por mes (0-11) para una clave; los meses no indicados valen 0. */
    private static <K> void assertRows(List<? extends Map<K, Integer>> actualRowsValues, K key,
            Map<Integer, Integer> expectedByMonth) {
        assertThat(actualRowsValues).hasSize(12);
        for (int month = 0; month < 12; month++) {
            assertThat(actualRowsValues.get(month).get(key))
                    .as("mes %d, clave %s", month, key)
                    .isEqualTo(expectedByMonth.getOrDefault(month, 0));
        }
    }

    private static List<Map<UUID, Integer>> doctorValues(DoctorAppointmentsCountDto dto) {
        assertThat(dto.rows()).extracting(MonthlyDoctorRowDto::month)
                .containsExactlyElementsOf(IntStream.range(0, 12).boxed().toList());
        return dto.rows().stream().map(MonthlyDoctorRowDto::values).toList();
    }

    private static List<Map<SpecialtyCode, Integer>> specialtyValues(SpecialtyAppointmentsCountDto dto) {
        assertThat(dto.rows()).extracting(MonthlySpecialtyRowDto::month)
                .containsExactlyElementsOf(IntStream.range(0, 12).boxed().toList());
        return dto.rows().stream().map(MonthlySpecialtyRowDto::values).toList();
    }

    @Test
    void contextShouldWireStatisticsUseCaseAndExternalService() {
        assertThat(applicationContext.getBean("getAppointmentStatisticsUseCase"))
                .isInstanceOf(GetAppointmentStatisticsUseCaseImpl.class);
        assertThat(applicationContext.getBean(GetAppointmentStatisticsUseCase.class)).isNotNull();
        assertThat(appointmentExternalService).isInstanceOf(AppointmentExternalServiceImpl.class);
    }

    @Test
    void byDoctorAgendadaShouldCountAllStatesOfTheYear() {
        DoctorAppointmentsCountDto result = appointmentExternalService.getAppointmentsCountByDoctor(YEAR, "AGENDADA");

        assertThat(result.doctorIds()).containsExactlyInAnyOrder(doctor1, doctor2);
        assertThat(result.doctorIds()).isSortedAccordingTo(
                Comparator.comparing(UUID::toString));
        List<Map<UUID, Integer>> values = doctorValues(result);
        assertRows(values, doctor1, Map.of(0, 2, 1, 1));
        assertRows(values, doctor2, Map.of(0, 1, 2, 1, 11, 1));
    }

    @Test
    void byDoctorAtendidaShouldCountOnlyAttended() {
        DoctorAppointmentsCountDto result = appointmentExternalService.getAppointmentsCountByDoctor(YEAR, "ATENDIDA");

        assertThat(result.doctorIds()).containsExactlyInAnyOrder(doctor1, doctor2);
        List<Map<UUID, Integer>> values = doctorValues(result);
        assertRows(values, doctor1, Map.of(0, 1));
        assertRows(values, doctor2, Map.of(0, 1));
    }

    @Test
    void bySpecialtyAgendadaShouldCountAllStatesOfTheYear() {
        SpecialtyAppointmentsCountDto result =
                appointmentExternalService.getAppointmentsCountBySpecialty(YEAR, "AGENDADA");

        assertThat(result.specialtys()).containsExactly(SpecialtyCode.FISIOTERAPIA, SpecialtyCode.TERAPIA_NEURAL);
        List<Map<SpecialtyCode, Integer>> values = specialtyValues(result);
        assertRows(values, SpecialtyCode.FISIOTERAPIA, Map.of(0, 2, 2, 1, 11, 1));
        assertRows(values, SpecialtyCode.TERAPIA_NEURAL, Map.of(0, 1, 1, 1));
    }

    @Test
    void bySpecialtyAtendidaShouldCountOnlyAttended() {
        SpecialtyAppointmentsCountDto result =
                appointmentExternalService.getAppointmentsCountBySpecialty(YEAR, "ATENDIDA");

        assertThat(result.specialtys()).containsExactly(SpecialtyCode.FISIOTERAPIA, SpecialtyCode.TERAPIA_NEURAL);
        List<Map<SpecialtyCode, Integer>> values = specialtyValues(result);
        assertRows(values, SpecialtyCode.FISIOTERAPIA, Map.of(0, 1));
        assertRows(values, SpecialtyCode.TERAPIA_NEURAL, Map.of(0, 1));
    }

    @Test
    void byMonthShouldCountOnlyAttendedAppointments() {
        List<MonthlyTotalAppointmentDto> result = appointmentExternalService.getAttendedAppointmentsCountByMonth(YEAR);

        assertThat(result).extracting(MonthlyTotalAppointmentDto::month)
                .containsExactlyElementsOf(IntStream.range(0, 12).boxed().toList());
        assertThat(result).extracting(MonthlyTotalAppointmentDto::total)
                .containsExactly(2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void cancellationDataShouldCountCanceladaPlusNoAsistioWhenStateIsNull() {
        assertCancellation(appointmentExternalService.getCancellationData(YEAR, null),
                Map.of(0, new int[]{3, 1}, 1, new int[]{1, 1}, 2, new int[]{1, 0}, 11, new int[]{1, 0}));
    }

    @Test
    void cancellationDataShouldCountOnlyCanceladaWhenRequested() {
        assertCancellation(appointmentExternalService.getCancellationData(YEAR, "CANCELADA"),
                Map.of(0, new int[]{3, 1}, 1, new int[]{1, 0}, 2, new int[]{1, 0}, 11, new int[]{1, 0}));
    }

    @Test
    void cancellationDataShouldCountOnlyNoAsistioWhenRequested() {
        assertCancellation(appointmentExternalService.getCancellationData(YEAR, "NO_ASISTIO"),
                Map.of(0, new int[]{3, 0}, 1, new int[]{1, 1}, 2, new int[]{1, 0}, 11, new int[]{1, 0}));
    }

    @Test
    void cancellationDataShouldCountOnlyAtendidaWhenRequested() {
        assertCancellation(appointmentExternalService.getCancellationData(YEAR, "ATENDIDA"),
                Map.of(0, new int[]{3, 2}, 1, new int[]{1, 0}, 2, new int[]{1, 0}, 11, new int[]{1, 0}));
    }

    @Test
    void adjacentYearsShouldOnlySeeTheirOwnBoundaryAppointment() {
        // 31-dic del año anterior → diciembre (mes 11) únicamente
        List<MonthlyTotalAppointmentDto> previous =
                appointmentExternalService.getAttendedAppointmentsCountByMonth(YEAR - 1);
        assertThat(previous).extracting(MonthlyTotalAppointmentDto::total)
                .containsExactly(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);

        // 1-ene del año siguiente → enero (mes 0) únicamente
        List<MonthlyTotalAppointmentDto> next =
                appointmentExternalService.getAttendedAppointmentsCountByMonth(YEAR + 1);
        assertThat(next).extracting(MonthlyTotalAppointmentDto::total)
                .containsExactly(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void yearWithoutAppointmentsShouldReturnTwelveZeroedMonthsAndNoKeys() {
        DoctorAppointmentsCountDto byDoctor = appointmentExternalService.getAppointmentsCountByDoctor(YEAR + 5, "AGENDADA");
        assertThat(byDoctor.doctorIds()).isEmpty();
        assertThat(byDoctor.rows()).hasSize(12).allSatisfy(r -> assertThat(r.values()).isEmpty());

        assertThat(appointmentExternalService.getAttendedAppointmentsCountByMonth(YEAR + 5))
                .hasSize(12).allSatisfy(r -> assertThat(r.total()).isZero());
        assertThat(appointmentExternalService.getCancellationData(YEAR + 5, null))
                .hasSize(12).allSatisfy(r -> {
                    assertThat(r.totalAppointments()).isZero();
                    assertThat(r.count()).isZero();
                });
    }

    @Test
    void invalidInputsShouldBeRejected() {
        assertThatThrownBy(() -> appointmentExternalService.getAppointmentsCountByDoctor(1999, "AGENDADA"))
                .isInstanceOf(InvalidStatisticsYearException.class);
        assertThatThrownBy(() -> appointmentExternalService.getAttendedAppointmentsCountByMonth(2101))
                .isInstanceOf(InvalidStatisticsYearException.class);
        assertThatThrownBy(() -> appointmentExternalService.getAppointmentsCountByDoctor(YEAR, null))
                .isInstanceOf(InvalidStatisticsStateException.class);
        assertThatThrownBy(() -> appointmentExternalService.getAppointmentsCountBySpecialty(YEAR, "CANCELADA"))
                .isInstanceOf(InvalidStatisticsStateException.class);
        assertThatThrownBy(() -> appointmentExternalService.getCancellationData(YEAR, "xyz"))
                .isInstanceOf(InvalidStatisticsStateException.class);
    }

    /** Siembra el escenario diario (2040-03-14/15/16) y devuelve los doctores D1..D4. */
    private UUID[] seedDailyScenario() {
        UUID d1 = newDoctor();
        UUID d2 = newDoctor();
        UUID d3 = newDoctor();
        UUID d4 = newDoctor();
        LocalDate day = LocalDate.of(YEAR, 3, 15);
        seedAppointment(d1, day, 8, AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);
        seedAppointment(d1, day, 9, AppointmentState.ATENDIDA, SpecialtyCode.FISIOTERAPIA);
        seedAppointment(d1, day, 10, AppointmentState.CANCELADA, SpecialtyCode.FISIOTERAPIA);
        seedAppointment(d2, day, 8, AppointmentState.CANCELADA, SpecialtyCode.FISIOTERAPIA);
        seedAppointment(d3, day, 8, AppointmentState.NO_ASISTIO, SpecialtyCode.FISIOTERAPIA);
        seedAppointment(d3, day, 9, AppointmentState.REPROGRAMADA, SpecialtyCode.FISIOTERAPIA);
        seedAppointment(d3, day, 10, AppointmentState.ATENDIDA, SpecialtyCode.FISIOTERAPIA);
        seedAppointment(d1, day.plusDays(1), 8, AppointmentState.ATENDIDA, SpecialtyCode.FISIOTERAPIA);
        seedAppointment(d4, day.minusDays(1), 8, AppointmentState.AGENDADA, SpecialtyCode.FISIOTERAPIA);
        return new UUID[]{d1, d2, d3, d4};
    }

    @Test
    void doctorsAppointmentCountByDateShouldExcludeCanceladaAndOmitDoctorsWithoutAppointments() {
        UUID[] d = seedDailyScenario();

        List<DoctorDailyCountDto> result =
                appointmentExternalService.getDoctorsAppointmentCountByDate(LocalDate.of(YEAR, 3, 15));

        // D2 (solo CANCELADA) y D4 (otra fecha) no aparecen; orden ascendente por doctorId (String)
        List<DoctorDailyCountDto> expected = new ArrayList<>(List.of(
                new DoctorDailyCountDto(d[0], 2), new DoctorDailyCountDto(d[2], 3)));
        expected.sort(Comparator.comparing(dto -> dto.doctorId().toString()));
        assertThat(result).containsExactlyElementsOf(expected);
    }

    @Test
    void doctorsAppointmentCountByDateShouldOnlyCountTheRequestedDate() {
        UUID[] d = seedDailyScenario();

        assertThat(appointmentExternalService.getDoctorsAppointmentCountByDate(LocalDate.of(YEAR, 3, 16)))
                .containsExactly(new DoctorDailyCountDto(d[0], 1));
        assertThat(appointmentExternalService.getDoctorsAppointmentCountByDate(LocalDate.of(YEAR, 3, 14)))
                .containsExactly(new DoctorDailyCountDto(d[3], 1));
    }

    @Test
    void doctorsAppointmentCountByDateShouldReturnEmptyListForADateWithoutAppointments() {
        seedDailyScenario();

        assertThat(appointmentExternalService.getDoctorsAppointmentCountByDate(LocalDate.of(YEAR, 3, 17))).isEmpty();
    }

    @Test
    void doctorsAppointmentCountByDateShouldRejectNullDate() {
        assertThatThrownBy(() -> appointmentExternalService.getDoctorsAppointmentCountByDate(null))
                .isInstanceOf(InvalidStatisticsDateException.class);
    }

    /** expected: mes → {totalAppointments, count}; los meses no indicados valen 0/0. */
    private static void assertCancellation(List<MonthlyCancellationDataDto> actual, Map<Integer, int[]> expected) {
        assertThat(actual).hasSize(12);
        for (int month = 0; month < 12; month++) {
            int[] values = expected.getOrDefault(month, new int[]{0, 0});
            assertThat(actual.get(month))
                    .as("mes %d", month)
                    .isEqualTo(new MonthlyCancellationDataDto(month, values[0], values[1]));
        }
    }
}
