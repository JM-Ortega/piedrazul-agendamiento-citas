package co.edu.unicauca.piedrazul.backend.notifications.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.MutableClock;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.StubProvider;
import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationDispatchException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.ClaimedSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * La consola es diagnóstico, no entrega: el OTP solo llega a ella sin proveedor real y con el
 * opt-in explícito, y aun así no queda registrado como entregado. Tampoco el resto de mensajes
 * impresos en consola cuenta como entregado. Datos exclusivamente sintéticos.
 */
class NotificationDispatcherServiceConsoleSafetyTest {

    private final InMemoryDispatchStore store = new InMemoryDispatchStore();
    private final MutableClock clock = new MutableClock(NOW);

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

        dispatch(notification, false, console);

        verify(console, never()).send(any(), any());
        assertThat(store.attempts()).isEmpty();
        assertFailedWithoutRetry(notification);
        assertCodeNotLogged();
    }

    @Test
    void otpWithoutRealProviderAndWithOptInIsDiagnosticOnly() {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(true));
        Notification notification = otp();

        dispatch(notification, true, console);

        verify(console, times(1)).send(any(), any());
        assertThat(logs()).anyMatch(line -> line.contains("[CONSOLE_DIAGNOSTIC]") && line.contains(SYNTHETIC_CODE));

        NotificationAttempt attempt = singleAttempt();
        assertThat(attempt.getChannel()).isEqualTo(NotificationChannel.CONSOLE);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(attempt.getFailureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(attempt.getErrorCode()).isEqualTo(ConsoleNotificationProvider.DIAGNOSTIC_ONLY_ERROR_CODE);
        assertThat(attempt.getDeliveredAt()).isNull();
        assertThat(attempt.getAcceptedAt()).isNull();

        assertFailedWithoutRetry(notification);
        // Solo la línea de diagnóstico lleva el código: los eventos del dispatcher no
        assertThat(logs().stream().filter(line -> line.contains(SYNTHETIC_CODE)))
                .allMatch(line -> line.startsWith("[CONSOLE_DIAGNOSTIC]"));
    }

    // ── Con proveedor real ──────────────────────────────────────────────

    @ParameterizedTest(name = "opt-in={0}")
    @ValueSource(booleans = {false, true})
    void otpAcceptedByRealProviderNeverReachesConsole(boolean optIn) {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        StubProvider email = StubProvider.email(() -> result(AttemptStatus.ACCEPTED, null));
        Notification notification = otp();

        ClaimedSchedule claim = dispatch(notification, optIn, email, console);

        assertThat(email.calls()).isEqualTo(1);
        verify(console, never()).send(any(), any());
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.ACCEPTED);
        assertThat(store.schedule(claim.scheduleId()).getStatus()).isEqualTo(ScheduleStatus.SENT);
        assertCodeNotLogged();
    }

    @ParameterizedTest(name = "opt-in={0}")
    @ValueSource(booleans = {false, true})
    void otpPermanentFailureOfRealProviderDoesNotFallBackToConsole(boolean optIn) {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        StubProvider email = StubProvider.email(() -> result(AttemptStatus.FAILED, FailureType.PERMANENT));
        Notification notification = otp();

        dispatch(notification, optIn, email, console);

        verify(console, never()).send(any(), any());
        assertThat(singleAttempt().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertFailedWithoutRetry(notification);
        assertCodeNotLogged();
    }

    @Test
    void otpFailureOfAProviderQualifiedForCodesDoesNotFallBackToConsoleEither() {
        // Que un proveedor sea apto para códigos (W0b) no cambia la regla de W0a.
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(true));
        StubProvider email = StubProvider.qualifiedEmail(() -> result(AttemptStatus.FAILED, FailureType.PERMANENT));
        Notification notification = otp();

        dispatch(notification, true, email, console);

        verify(console, never()).send(any(), any());
        assertFailedWithoutRetry(notification);
        assertCodeNotLogged();
    }

    @Test
    void otpWithRealProviderRetriesExhaustedDoesNotFallBackToConsole() {
        // Fallo transitorio probado (no de aceptación desconocida) en el último intento permitido.
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(true));
        StubProvider email = StubProvider.email(() -> {
            throw new NotificationDispatchException(NotificationDispatchException.CIRCUIT_OPEN, "circuito sintético");
        });
        Notification notification = otp("sintetico@example.test", NOW.plus(Duration.ofHours(1)));
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
        NotificationDispatcherService dispatcher = dispatcher(store, clock, true, email, console);

        // Tres intentos de EMAIL: los dos primeros programan reintento; el tercero agota la política.
        for (int run = 0; run < 3; run++) {
            dispatcher.dispatch(claim);
            NotificationSchedule schedule = store.schedule(claim.scheduleId());

            if (schedule.getStatus() == ScheduleStatus.PENDING) {
                clock.set(schedule.getNextRetryAt());
                claim = store.reclaim(claim.scheduleId(), clock.instant());
            }
        }

        assertThat(email.calls()).isEqualTo(3);
        verify(console, never()).send(any(), any());
        assertFailedWithoutRetry(notification);
        assertCodeNotLogged();
    }

    // ── Notificaciones no sensibles ─────────────────────────────────────

    @ParameterizedTest(name = "opt-in={0}")
    @ValueSource(booleans = {false, true})
    void appointmentWithoutRealProviderIsPrintedButNotDelivered(boolean optIn) {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        Notification notification = appointment();

        dispatch(notification, optIn, console);

        verify(console, times(1)).send(any(), any());
        assertThat(logs()).anyMatch(line -> line.contains("[CONSOLE_NOTIFICATION]") && line.contains("Paciente Sintético"));

        NotificationAttempt attempt = singleAttempt();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo(ConsoleNotificationProvider.DIAGNOSTIC_ONLY_ERROR_CODE);
        assertThat(attempt.getDeliveredAt()).isNull();
        assertFailedWithoutRetry(notification);
    }

    @Test
    void appointmentStillFallsBackToConsoleAfterRealProviderFailure() {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(false));
        StubProvider email = StubProvider.email(() -> result(AttemptStatus.FAILED, FailureType.PERMANENT));
        Notification notification = appointment();

        dispatch(notification, false, email, console);

        verify(console, times(1)).send(any(), any());
        assertThat(store.notification(notification.getId()).getStatus())
                .isEqualTo(NotificationStatus.FAILED)
                .isNotEqualTo(NotificationStatus.DELIVERED);
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private ClaimedSchedule dispatch(Notification notification, boolean optIn, NotificationProvider... providers) {
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
        dispatcher(store, clock, optIn, providers).dispatch(claim);
        return claim;
    }

    private void assertFailedWithoutRetry(Notification notification) {
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.FAILED);
        NotificationSchedule schedule = store.schedule(
                store.appliedWrites.get(store.appliedWrites.size() - 1).scheduleId());
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.FAILED);
    }

    private void assertCodeNotLogged() {
        assertThat(logs()).isNotEmpty().noneMatch(line -> line.contains(SYNTHETIC_CODE));
    }

    private NotificationAttempt singleAttempt() {
        List<NotificationAttempt> attempts = store.attempts();
        assertThat(attempts).hasSize(1);
        return attempts.get(0);
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
