package co.edu.unicauca.piedrazul.backend.notifications.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationDispatchException;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.RetryPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.*;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * La consola es diagnóstico, no entrega: el OTP solo llega a ella sin proveedor real y con el
 * opt-in explícito, y aun así no queda registrado como entregado. Datos exclusivamente sintéticos.
 */
class NotificationDispatcherServiceConsoleSafetyTest {

    private static final String SYNTHETIC_CODE = "000111";
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final NotificationScheduleRepository scheduleRepository = mock(NotificationScheduleRepository.class);
    private final NotificationAttemptRepository attemptRepository = mock(NotificationAttemptRepository.class);
    private final TemplateRenderer templateRenderer = new TemplateRenderer(new InMemoryNotificationTemplateProvider());

    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger.detachAppender(appender);
    }

    // ── Sin proveedor real ──────────────────────────────────────────────

    @Test
    void otpWithoutRealProviderAndWithoutOptInNeverInvokesConsole() {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(false));
        Notification notification = otp();
        NotificationSchedule schedule = schedule(notification);

        dispatcher(false, console).dispatch(notification, schedule, otpVariables(), NOW);

        verify(console, never()).send(any(), any());
        verify(attemptRepository, never()).save(any());
        assertFailedWithoutRetry(notification, schedule);
        assertCodeNotLogged();
    }

    @Test
    void otpWithoutRealProviderAndWithOptInIsDiagnosticOnly() {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(true));
        Notification notification = otp();
        NotificationSchedule schedule = schedule(notification);

        dispatcher(true, console).dispatch(notification, schedule, otpVariables(), NOW);

        verify(console, times(1)).send(any(), any());
        assertThat(logs()).anyMatch(line -> line.contains("[CONSOLE_DIAGNOSTIC]") && line.contains(SYNTHETIC_CODE));

        NotificationAttempt attempt = singleSavedAttempt();
        assertThat(attempt.getChannel()).isEqualTo(NotificationChannel.CONSOLE);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(attempt.getFailureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(attempt.getErrorCode()).isEqualTo(ConsoleNotificationProvider.DIAGNOSTIC_ONLY_ERROR_CODE);
        assertThat(attempt.getDeliveredAt()).isNull();
        assertThat(attempt.getAcceptedAt()).isNull();

        assertFailedWithoutRetry(notification, schedule);
        // Solo la línea de diagnóstico lleva el código: los eventos del dispatcher no
        assertThat(logs().stream().filter(line -> line.contains(SYNTHETIC_CODE)))
                .allMatch(line -> line.startsWith("[CONSOLE_DIAGNOSTIC]"));
    }

    // ── Con proveedor real ──────────────────────────────────────────────

    @ParameterizedTest(name = "opt-in={0}")
    @ValueSource(booleans = {false, true})
    void otpAcceptedByRealProviderNeverReachesConsole(boolean optIn) {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        StubEmailProvider email = new StubEmailProvider(() -> emailResult(AttemptStatus.ACCEPTED, null));
        Notification notification = otp();
        NotificationSchedule schedule = schedule(notification);

        dispatcher(optIn, email, console).dispatch(notification, schedule, otpVariables(), NOW);

        assertThat(email.calls).isEqualTo(1);
        verify(console, never()).send(any(), any());
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.ACCEPTED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SENT);
        assertCodeNotLogged();
    }

    @ParameterizedTest(name = "opt-in={0}")
    @ValueSource(booleans = {false, true})
    void otpPermanentFailureOfRealProviderDoesNotFallBackToConsole(boolean optIn) {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        StubEmailProvider email = new StubEmailProvider(() -> emailResult(AttemptStatus.FAILED, FailureType.PERMANENT));
        Notification notification = otp();
        NotificationSchedule schedule = schedule(notification);

        dispatcher(optIn, email, console).dispatch(notification, schedule, otpVariables(), NOW);

        verify(console, never()).send(any(), any());
        assertThat(singleSavedAttempt().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertFailedWithoutRetry(notification, schedule);
        assertCodeNotLogged();
    }

    @Test
    void otpFailureOfAProviderQualifiedForCodesDoesNotFallBackToConsoleEither() {
        // Que un proveedor sea apto para códigos (W0b) no cambia la regla de W0a.
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(true));
        StubEmailProvider email = new StubEmailProvider(
                () -> emailResult(AttemptStatus.FAILED, FailureType.PERMANENT), true);
        Notification notification = otp();
        NotificationSchedule schedule = schedule(notification);

        dispatcher(true, email, console).dispatch(notification, schedule, otpVariables(), NOW);

        verify(console, never()).send(any(), any());
        assertFailedWithoutRetry(notification, schedule);
        assertCodeNotLogged();
    }

    @Test
    void otpWithRealProviderRetriesExhaustedDoesNotFallBackToConsole() {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(true));
        StubEmailProvider email = new StubEmailProvider(() -> {
            throw new NotificationDispatchException("SYNTHETIC_TIMEOUT", "fallo sintético");
        });
        // Dos intentos de EMAIL ya hechos: la política no programa otro reintento
        when(attemptRepository.countByNotificationIdAndChannel(any(), eq(NotificationChannel.EMAIL))).thenReturn(2);
        Notification notification = otp();
        NotificationSchedule schedule = schedule(notification);

        dispatcher(true, email, console).dispatch(notification, schedule, otpVariables(), NOW);

        verify(console, never()).send(any(), any());
        assertFailedWithoutRetry(notification, schedule);
        assertCodeNotLogged();
    }

    // ── Notificaciones no sensibles: comportamiento existente ───────────

    @ParameterizedTest(name = "opt-in={0}")
    @ValueSource(booleans = {false, true})
    void appointmentWithoutRealProviderKeepsConsoleDelivered(boolean optIn) {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        Notification notification = appointment();
        NotificationSchedule schedule = schedule(notification);

        dispatcher(optIn, console).dispatch(notification, schedule, Map.of("patientName", "Paciente Sintético"), NOW);

        verify(console, times(1)).send(any(), any());
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SENT);
        assertThat(logs()).anyMatch(line -> line.contains("[CONSOLE_NOTIFICATION]") && line.contains("Paciente Sintético"));
    }

    @Test
    void appointmentStillFallsBackToConsoleAfterRealProviderFailure() {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(false));
        StubEmailProvider email = new StubEmailProvider(() -> emailResult(AttemptStatus.FAILED, FailureType.PERMANENT));
        Notification notification = appointment();
        NotificationSchedule schedule = schedule(notification);

        dispatcher(false, email, console).dispatch(notification, schedule, Map.of("patientName", "Paciente Sintético"), NOW);

        verify(console, times(1)).send(any(), any());
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SENT);
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private NotificationDispatcherService dispatcher(boolean optIn, NotificationProvider... providers) {
        return new NotificationDispatcherService(
                notificationRepository,
                scheduleRepository,
                attemptRepository,
                new ProviderRegistry(List.of(providers)),
                templateRenderer,
                new RetryPolicy(),
                new ChannelFallbackPolicy(),
                optIn
        );
    }

    private void assertFailedWithoutRetry(Notification notification, NotificationSchedule schedule) {
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.FAILED);
        assertThat(schedule.getNextRetryAt()).isNull();
    }

    private void assertCodeNotLogged() {
        assertThat(logs()).isNotEmpty().noneMatch(line -> line.contains(SYNTHETIC_CODE));
    }

    private NotificationAttempt singleSavedAttempt() {
        ArgumentCaptor<NotificationAttempt> captor = ArgumentCaptor.forClass(NotificationAttempt.class);
        verify(attemptRepository, atLeastOnce()).save(captor.capture());
        List<NotificationAttempt> distinct = captor.getAllValues().stream().distinct().toList();
        assertThat(distinct).hasSize(1);
        return distinct.get(0);
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static Map<String, String> otpVariables() {
        return Map.of("code", SYNTHETIC_CODE, "expirationMinutes", "5", "destination", "+570000000000");
    }

    private static Notification otp() {
        return notification(NotificationType.OTP_CODE, AggregateType.VERIFICATION);
    }

    private static Notification appointment() {
        return notification(NotificationType.APPOINTMENT_SCHEDULED, AggregateType.APPOINTMENT);
    }

    private static Notification notification(NotificationType type, AggregateType aggregateType) {
        return Notification.create(
                type,
                new AggregateReference(aggregateType, UUID.randomUUID()),
                new RecipientSnapshot(UUID.randomUUID(), "Sintético", "+570000000000", "sintetico@example.test",
                        Locale.forLanguageTag("es-CO"), null),
                // Como el orquestador: el orden por defecto queda fijado al encolar
                new ChannelPreference(new ChannelFallbackPolicy().resolveChannels(type)),
                Map.of(),
                NOW
        );
    }

    private static NotificationSchedule schedule(Notification notification) {
        return NotificationSchedule.create(notification.getId(), NOW, NOW);
    }

    private static NotificationSendResult emailResult(AttemptStatus status, FailureType failureType) {
        return new NotificationSendResult(
                "stub-email",
                NotificationChannel.EMAIL,
                status == AttemptStatus.FAILED ? null : "stub-" + UUID.randomUUID(),
                status,
                failureType,
                failureType == null ? null : "SYNTHETIC_REJECTED",
                failureType == null ? null : "rechazo sintético"
        );
    }

    private static final class StubEmailProvider implements NotificationProvider {
        private final Supplier<NotificationSendResult> behavior;
        private final boolean qualifiesForOtp;
        private int calls;

        private StubEmailProvider(Supplier<NotificationSendResult> behavior) {
            this(behavior, false);
        }

        private StubEmailProvider(Supplier<NotificationSendResult> behavior, boolean qualifiesForOtp) {
            this.behavior = behavior;
            this.qualifiesForOtp = qualifiesForOtp;
        }

        @Override
        public boolean qualifiesForOtpDelivery() {
            return qualifiesForOtp;
        }

        @Override
        public NotificationChannel channel() {
            return NotificationChannel.EMAIL;
        }

        @Override
        public String providerName() {
            return "stub-email";
        }

        @Override
        public NotificationSendResult send(ChannelMessage message, RecipientSnapshot recipient) {
            calls++;
            return behavior.get();
        }
    }
}
