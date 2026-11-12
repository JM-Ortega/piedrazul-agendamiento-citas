package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.PatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CancelAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateAppointmentStatusUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateExpiredAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.ResolvedPatient;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;

/**
 * Base de los IT de concurrencia del módulo de citas: contexto Spring Boot completo (con el
 * {@code AppointmentConfig} y la configuración transaccional reales) sobre el PostgreSQL de
 * Testcontainers migrado con Flyway.
 *
 * <p><b>Técnica de determinismo.</b> Nada depende de {@code sleep}. Una operación se ejecuta en su
 * propio hilo con una {@link Pause} armada: cuando el hilo llega a un punto de su sección crítica
 * (justo antes de guardar, o justo tras seleccionar los candidatos del cierre nocturno) avisa y se
 * detiene hasta que el test lo libera. Mientras está detenida, el test lanza la operación
 * competidora y comprueba, con la vista {@code pg_stat_activity} de PostgreSQL, que esa segunda
 * transacción quedó <i>esperando un bloqueo</i>; solo entonces libera a la primera. Es una pausa de
 * una sola parte (no una barrera que exija que ambas lleguen), por lo que no se bloquea cuando la
 * serialización funciona; y, sin serialización, la competidora termina sin esperar y el test falla.
 *
 * <p>El punto de pausa se engancha en {@link AppointmentMapper} (espía): {@code toEntity} se invoca
 * dentro de {@code save}, es decir, dentro de la transacción y después de cargar/validar.
 *
 * <p>Esta base solo usa la API pública previa a la corrección (servicios y casos de uso), de modo
 * que los tests derivados pueden ejecutarse sin cambios contra una línea base sin corregir.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AppointmentConcurrencyITSupport.TestBeans.class)
abstract class AppointmentConcurrencyITSupport extends PostgresIntegrationSupport {

    /** Puntos de la sección crítica en los que se puede detener una operación. */
    enum Phase {
        /** Dentro de {@code save}, con la transacción abierta y la entidad ya validada. */
        BEFORE_SAVE,
        /** Tras seleccionar (y mapear) los candidatos, antes de modificarlos. */
        AFTER_LOAD
    }

    @TestConfiguration
    static class TestBeans {
        /** Evita resolver el issuer real de Keycloak al arrancar; no se invoca (se usa jwt() de MockMvc). */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("No se decodifica JWT real en este test");
            };
        }

        @Bean
        CommittedEvents committedEvents() {
            return new CommittedEvents();
        }
    }

    /** Eventos que llegaron a publicarse tras un COMMIT real (los de transacciones revertidas no aparecen). */
    static class CommittedEvents {
        // Se accede por métodos (no por campos): el bean puede llegar envuelto en un proxy.
        private final List<AppointmentScheduledEvent> notifications = new CopyOnWriteArrayList<>();
        private final List<ScheduledAppointmentEvent> audits = new CopyOnWriteArrayList<>();

        public List<AppointmentScheduledEvent> notifications() {
            return notifications;
        }

        public List<ScheduledAppointmentEvent> audits() {
            return audits;
        }

        public void clear() {
            notifications.clear();
            audits.clear();
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(AppointmentScheduledEvent event) {
            notifications.add(event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void on(ScheduledAppointmentEvent event) {
            audits.add(event);
        }
    }

    /** Pausa de una sola parte en un hilo de prueba. */
    static final class Pause {
        final Phase phase;
        final CountDownLatch reached = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean fired = new AtomicBoolean();

        Pause(Phase phase) {
            this.phase = phase;
        }

        void awaitReached() throws InterruptedException {
            assertThat(reached.await(30, TimeUnit.SECONDS))
                    .as("la operación pausada no llegó a su sección crítica").isTrue();
        }

        void release() {
            release.countDown();
        }
    }

    private static final ThreadLocal<Pause> ARMED = new ThreadLocal<>();

    // Fronteras externas que no son parte de la propiedad bajo prueba
    @MockitoBean
    KeycloakUserService keycloakUserService;
    @MockitoBean
    VerificationCodeSender verificationCodeSender;
    // Siembran datos vía Keycloak real al arrancar; se anulan igual que en AppointmentControllerIT
    @MockitoBean
    DoctorDataInitializer doctorDataInitializer;
    @MockitoBean
    PatientDataInitializer patientDataInitializer;

    @MockitoSpyBean
    AppointmentMapper appointmentMapper;

    @Autowired
    AppointmentSchedulingService schedulingService;
    @Autowired
    CancelAppointmentUseCase cancelUseCase;
    @Autowired
    UpdateAppointmentStatusUseCase statusUseCase;
    @Autowired
    UpdateExpiredAppointmentsUseCase expiredUseCase;
    @Autowired
    AppointmentJpaRepository appointmentJpaRepository;
    @Autowired
    DoctorRepository doctorRepository;
    @Autowired
    PatientRepository patientRepository;
    @Autowired
    PersonExternalService personExternalService;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CommittedEvents committedEvents;

    private ExecutorService executor;
    private final AtomicLong documentSequence = new AtomicLong(System.nanoTime());

    @BeforeEach
    void installPauseHooks() {
        // Los espías de @MockitoSpyBean se reinician tras cada test: se vuelven a instalar.
        doAnswer(invocation -> {
            pauseIfArmed(Phase.BEFORE_SAVE);
            return invocation.callRealMethod();
        }).when(appointmentMapper).toEntity(org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            pauseIfArmed(Phase.AFTER_LOAD);
            return result;
        }).when(appointmentMapper).toDomain(org.mockito.ArgumentMatchers.any());
        executor = Executors.newCachedThreadPool();
        committedEvents.clear();
    }

    @AfterEach
    void shutdownExecutor() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                .as("quedaron hilos de prueba sin terminar (posible bloqueo)").isTrue();
    }

    private static void pauseIfArmed(Phase phase) throws InterruptedException {
        Pause pause = ARMED.get();
        if (pause != null && pause.phase == phase && pause.fired.compareAndSet(false, true)) {
            pause.reached.countDown();
            if (!pause.release.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La pausa de prueba no fue liberada");
            }
        }
    }

    // ------------------------------------------------------------------ ejecución

    <T> Future<T> run(Callable<T> task) {
        return executor.submit(task);
    }

    /** Ejecuta {@code task} en su propio hilo, detenido en {@code pause}. */
    <T> Future<T> runPaused(Pause pause, Callable<T> task) {
        return executor.submit(() -> {
            ARMED.set(pause);
            try {
                return task.call();
            } finally {
                ARMED.remove();
            }
        });
    }

    /** Resultado de la operación: {@code null} si terminó bien, o la excepción de dominio que lanzó. */
    Throwable outcome(Future<?> future) throws InterruptedException {
        try {
            future.get(60, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (java.util.concurrent.TimeoutException e) {
            return fail("la operación no terminó (posible deadlock o bloqueo no liberado)");
        }
    }

    // ------------------------------------------------------------------ evidencia de PostgreSQL

    /**
     * Espera hasta que algún backend de PostgreSQL esté bloqueado esperando un lock cuyo
     * {@code wait_event} esté en {@code expectedWaitEvents}. Falla si la operación competidora termina
     * antes sin haber esperado (la señal de que no hay serialización).
     */
    void awaitBlockedOnLock(Future<?> competitor, Set<String> expectedWaitEvents) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            List<String> waiting = jdbc.queryForList(
                    "select wait_event from pg_stat_activity "
                            + "where datname = current_database() and pid <> pg_backend_pid() "
                            + "and wait_event_type = 'Lock'", String.class);
            if (waiting.stream().anyMatch(expectedWaitEvents::contains)) {
                assertThat(competitor.isDone())
                        .as("la operación competidora no debería haber terminado mientras espera el lock")
                        .isFalse();
                return;
            }
            if (competitor.isDone()) {
                fail("La operación competidora terminó sin esperar ningún bloqueo (se esperaba "
                        + expectedWaitEvents + "): no hay serialización. Resultado: " + outcomeDescription(competitor));
            }
            Thread.sleep(20);
        }
        fail("Ningún backend llegó a esperar un lock " + expectedWaitEvents + " en 20 s");
    }

    private String outcomeDescription(Future<?> future) {
        try {
            future.get();
            return "éxito";
        } catch (Exception e) {
            return String.valueOf(e.getCause());
        }
    }

    static final Set<String> ADVISORY = Set.of("advisory");
    static final Set<String> ROW_LOCK = Set.of("transactionid", "tuple");

    // ------------------------------------------------------------------ datos sintéticos

    private String uniqueDocument() {
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    UUID newDoctor() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Doc", "Tor", "3000000001", null, null);
        doctorRepository.save(new Doctor(person.id(), LocalDate.now(), LocalDate.now().plusYears(1), 4, true, 30));
        return person.id();
    }

    UUID newPatient() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Ana", "Ruiz", "3001234567", null, null);
        patientRepository.save(new Patient(person.id(), Sex.FEMENINO, LocalDate.now().minusYears(30), null));
        return person.id();
    }

    /** Estrategia que devuelve un paciente ya existente (en lugar de aprovisionarlo). */
    PatientResolutionStrategy resolving(UUID patientId) {
        PatientInfo info = PatientInfo.of(DocumentType.CEDULA, uniqueDocument(), "Ana", "Ruiz", "3001234567",
                Gender.FEMENINO, LocalDate.now().minusYears(30), null, null);
        return context -> new ResolvedPatient(patientId, info);
    }

    UUID seedAppointment(UUID doctor, UUID patient, SpecialtyCode specialty, AppointmentState state,
                         LocalDate date, LocalTime time, SchedulingOrigin origin) {
        return appointmentJpaRepository.save(new AppointmentEntity(
                null, doctor, patient, specialty, state, date, time, origin)).getIdAppointment();
    }

    /** Reserva MANUAL de un paciente ya existente. */
    Callable<Void> bookManual(UUID doctor, UUID patient, SpecialtyCode specialty, LocalDate date, LocalTime time) {
        return () -> {
            schedulingService.scheduleManual(PatientSchedulingContext.autonomous(patient), doctor, specialty, date,
                    new AppointmentTime(time), UUID.randomUUID(), resolving(patient));
            return null;
        };
    }

    /** Reserva AUTONOMA de un paciente ya existente. */
    Callable<Void> bookAutonomous(UUID doctor, UUID patient, SpecialtyCode specialty, LocalDate date, LocalTime time) {
        return () -> {
            schedulingService.scheduleAutonomous(PatientSchedulingContext.autonomous(patient), doctor, specialty,
                    date, new AppointmentTime(time), UUID.randomUUID(), resolving(patient));
            return null;
        };
    }

    List<AppointmentEntity> appointmentsOf(UUID doctor, LocalDate date) {
        return appointmentJpaRepository.findByIdDoctorAndDate(doctor, date);
    }

    AppointmentState stateOf(UUID appointmentId) {
        return appointmentJpaRepository.findById(appointmentId).orElseThrow().getAppointmentState();
    }

    long clinicalHistoryRows(UUID appointmentId) {
        Long count = jdbc.queryForObject(
                "select count(*) from piedrazul.clinical_history where appointment_id = ?", Long.class, appointmentId);
        return count == null ? 0 : count;
    }

    /** Lo publicado tras commit para ese doctor debe coincidir con lo realmente persistido. */
    void assertCommittedEventsMatch(UUID doctor, LocalDate date) {
        Set<UUID> committed = appointmentsOf(doctor, date).stream()
                .map(AppointmentEntity::getIdAppointment).collect(java.util.stream.Collectors.toSet());
        assertThat(committedEvents.notifications().stream().filter(e -> doctor.equals(e.doctorId()))
                .map(AppointmentScheduledEvent::appointmentId))
                .as("eventos de notificación publicados vs citas confirmadas")
                .containsExactlyInAnyOrderElementsOf(committed);
        assertThat(committedEvents.audits().stream().map(ScheduledAppointmentEvent::citaId)
                .filter(committed::contains))
                .as("eventos de auditoría de las citas confirmadas")
                .containsExactlyInAnyOrderElementsOf(committed);
    }
}
