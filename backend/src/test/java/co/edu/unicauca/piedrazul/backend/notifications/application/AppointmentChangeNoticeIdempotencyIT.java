package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentCancelledByStaffEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentRescheduledEvent;
import co.edu.unicauca.piedrazul.backend.notifications.application.eventhandler.AppointmentNotificationEventHandler;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ReminderScheduleCalculator;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationAttemptMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.mappers.NotificationScheduleMapper;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.adapter.NotificationScheduleRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Avisos de cancelación y reagendamiento sobre PostgreSQL real: una misma clave de idempotencia
 * (agregado + tipo) nunca produce una segunda fila, sea cual sea el estado de la anterior, y una
 * carrera por la misma clave se resuelve con la reentrega del evento. Datos exclusivamente
 * sintéticos.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        NotificationRepositoryAdapter.class,
        NotificationScheduleRepositoryAdapter.class,
        NotificationMapper.class,
        NotificationScheduleMapper.class,
        NotificationAttemptMapper.class
})
// Sin transacción envolvente: cada llamada confirma por su cuenta, como el listener real.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppointmentChangeNoticeIdempotencyIT extends PostgresIntegrationSupport {

    private static final int RACE_ROUNDS = 25;

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationScheduleRepository scheduleRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Flyway flyway;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final List<UUID> aggregates = new ArrayList<>();
    private AppointmentNotificationEventHandler handler;
    private UUID person;

    @BeforeEach
    void setUp() {
        NotificationFactory factory = new NotificationFactory();
        NotificationOrchestrator orchestrator = new NotificationOrchestrator(notificationRepository, factory,
                new NotificationSchedulerService(factory, scheduleRepository), new ChannelFallbackPolicy());
        handler = new AppointmentNotificationEventHandler(
                new NotificationModuleApiImpl(orchestrator, null, Clock.systemUTC()),
                new ReminderScheduleCalculator(ZoneId.of("America/Bogota")), Clock.systemUTC());

        person = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO piedrazul.person (id, identification_type, identification, first_name, last_name, phone, email)
                VALUES (?, 'CEDULA', ?, 'Sintético', 'Prueba', '+570000000000', 'sintetico@example.test')""",
                person, "notice-" + person);
    }

    @AfterEach
    void cleanUp() {
        workers.shutdownNow();
        for (UUID aggregate : aggregates) {
            jdbc.update("""
                    DELETE FROM piedrazul.notification_schedule WHERE notification_id IN
                    (SELECT id FROM piedrazul.notification WHERE aggregate_id = ?)""", aggregate);
            jdbc.update("DELETE FROM piedrazul.notification WHERE aggregate_id = ?", aggregate);
        }
        // Solo la persona sintética propia, una vez retirados sus dependientes
        jdbc.update("DELETE FROM piedrazul.person WHERE id = ?", person);
    }

    // ── Catálogo ────────────────────────────────────────────────────────

    @Test
    void theCatalogHasTheNewTypesAfterACleanMigration() {
        assertThat(jdbc.queryForList("SELECT code FROM piedrazul.notification_type", String.class))
                .contains("APPOINTMENT_CANCELLED", "APPOINTMENT_RESCHEDULED", "APPOINTMENT_REMINDER_2_DAYS", "OTP_CODE");
    }

    /**
     * Verificación de solo lectura: la repetible del catálogo quedó aplicada con éxito, no hay
     * migraciones pendientes y cada tipo nuevo existe una sola vez. La re-aplicación sobre una base
     * ya migrada la garantiza el {@code ON CONFLICT DO NOTHING} de la semilla; no se altera el
     * historial de Flyway ni las filas de catálogo, que comparten las demás clases de integración.
     */
    @Test
    void theRepeatableCatalogIsAppliedAndLeavesEachNewTypeExactlyOnce() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(java.util.Arrays.stream(flyway.info().applied())
                .filter(m -> m.getVersion() == null && m.getScript().contains("seed_catalogs"))
                .allMatch(m -> m.getState().isApplied() && !m.getState().isFailed())).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM piedrazul.notification_type
                WHERE code IN ('APPOINTMENT_CANCELLED', 'APPOINTMENT_RESCHEDULED')""", Integer.class)).isEqualTo(2);
    }

    // ── Eventos repetidos tras cualquier resultado anterior ─────────────

    @ParameterizedTest(name = "estado previo {0}")
    @EnumSource(NotificationStatus.class)
    void aRepeatedCancellationEventNeverCreatesASecondRowWhateverThePriorStatus(NotificationStatus prior) {
        AppointmentCancelledByStaffEvent event = cancelled();

        handler.onAppointmentCancelledByStaff(event);
        setStatus(event.appointmentId(), prior);
        handler.onAppointmentCancelledByStaff(event);

        assertOneRow(event.appointmentId(), "APPOINTMENT_CANCELLED", prior);
    }

    @ParameterizedTest(name = "estado previo {0}")
    @EnumSource(NotificationStatus.class)
    void aRepeatedReschedulingEventNeverCreatesASecondRowWhateverThePriorStatus(NotificationStatus prior) {
        AppointmentRescheduledEvent event = rescheduled();

        handler.onAppointmentRescheduled(event);
        setStatus(event.previousAppointmentId(), prior);
        handler.onAppointmentRescheduled(event);

        assertOneRow(event.previousAppointmentId(), "APPOINTMENT_RESCHEDULED", prior);
    }

    @Test
    void cancellationAndReschedulingNoticesOfTheSameAppointmentAreIndependentKeys() {
        AppointmentCancelledByStaffEvent cancelled = cancelled();
        AppointmentRescheduledEvent rescheduled = new AppointmentRescheduledEvent(cancelled.appointmentId(),
                UUID.randomUUID(), person, "Paciente Sintético", "+570000000000", "sintetico@example.test",
                "Dr. Anterior", LocalDate.of(2026, 3, 10), LocalTime.of(8, 0), "FISIOTERAPIA",
                "Dra. Nueva", LocalDate.of(2026, 3, 12), LocalTime.of(15, 0), "QUIROPRAXIA");

        handler.onAppointmentCancelledByStaff(cancelled);
        handler.onAppointmentRescheduled(rescheduled);

        assertThat(types(cancelled.appointmentId())).containsExactlyInAnyOrder(
                "APPOINTMENT_CANCELLED", "APPOINTMENT_RESCHEDULED");
    }

    // ── Carrera por la misma clave ──────────────────────────────────────

    @Test
    void aRaceOnTheSameKeyLeavesOneRowAndTheRedeliveryResolvesIt() throws Exception {
        for (int round = 0; round < RACE_ROUNDS; round++) {
            AppointmentRescheduledEvent event = rescheduled();
            CyclicBarrier barrier = new CyclicBarrier(2);

            List<Future<Throwable>> outcomes = new ArrayList<>();
            for (int worker = 0; worker < 2; worker++) {
                outcomes.add(workers.submit(() -> {
                    barrier.await();
                    try {
                        handler.onAppointmentRescheduled(event);
                        return null;
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                }));
            }
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> outcome : outcomes) {
                Throwable failure = outcome.get();
                if (failure != null) {
                    failures.add(failure);
                }
            }

            // Al menos uno ganó; si el otro perdió la carrera, falló sin dejar nada a medias
            assertThat(failures.size()).isLessThanOrEqualTo(1);
            assertThat(count(event.previousAppointmentId(), "APPOINTMENT_RESCHEDULED")).isEqualTo(1);

            // La reentrega del evento perdedor ya no falla ni duplica
            handler.onAppointmentRescheduled(event);
            assertThat(count(event.previousAppointmentId(), "APPOINTMENT_RESCHEDULED")).isEqualTo(1);
        }
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private AppointmentCancelledByStaffEvent cancelled() {
        UUID appointmentId = UUID.randomUUID();
        aggregates.add(appointmentId);
        return new AppointmentCancelledByStaffEvent(appointmentId, person, "Paciente Sintético", "+570000000000",
                "sintetico@example.test", "Dra. Sintética", LocalDate.of(2026, 3, 10), LocalTime.of(14, 30),
                "FISIOTERAPIA");
    }

    private AppointmentRescheduledEvent rescheduled() {
        UUID previousId = UUID.randomUUID();
        aggregates.add(previousId);
        return new AppointmentRescheduledEvent(previousId, UUID.randomUUID(), person, "Paciente Sintético",
                "+570000000000", "sintetico@example.test", "Dr. Anterior", LocalDate.of(2026, 3, 10),
                LocalTime.of(8, 0), "FISIOTERAPIA", "Dra. Nueva", LocalDate.of(2026, 3, 12), LocalTime.of(15, 0),
                "QUIROPRAXIA");
    }

    private void setStatus(UUID aggregateId, NotificationStatus status) {
        jdbc.update("UPDATE piedrazul.notification SET status = ? WHERE aggregate_id = ?", status.name(), aggregateId);
    }

    private void assertOneRow(UUID aggregateId, String type, NotificationStatus status) {
        assertThat(count(aggregateId, type)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM piedrazul.notification WHERE aggregate_id = ?",
                String.class, aggregateId)).isEqualTo(status.name());
    }

    private int count(UUID aggregateId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM piedrazul.notification WHERE aggregate_id = ? AND type_code = ?",
                Integer.class, aggregateId, type);
    }

    private List<String> types(UUID aggregateId) {
        return jdbc.queryForList("SELECT type_code FROM piedrazul.notification WHERE aggregate_id = ?",
                String.class, aggregateId);
    }
}
