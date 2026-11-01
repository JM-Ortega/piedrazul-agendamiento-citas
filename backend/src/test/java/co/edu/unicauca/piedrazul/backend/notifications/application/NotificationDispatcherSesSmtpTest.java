package co.edu.unicauca.piedrazul.backend.notifications.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.MutableClock;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.ClaimedSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses.SesSmtpTestKit.LocalProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;

import static co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * El despachador real con el proveedor SES real contra un servidor SMTP local: la posible
 * aceptación no se reintenta ni cae a la consola, y con SES registrado nada sensible llega a ella.
 */
@Timeout(60)
class NotificationDispatcherSesSmtpTest {

    private static final String PATIENT = "Paciente Sintético";

    private final InMemoryDispatchStore store = new InMemoryDispatchStore();
    private final MutableClock clock = new MutableClock(NOW);
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LocalProvider ses;

    @BeforeEach
    void attachAppender() {
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() throws Exception {
        rootLogger.detachAppender(appender);

        if (ses != null) {
            ses.close();
        }
    }

    private ClaimedSchedule dispatch(Notification notification, boolean optIn, ConsoleNotificationProvider console) {
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
        dispatcher(store, clock, optIn, ses.provider(), console).dispatch(claim);
        return claim;
    }

    private NotificationAttempt singleAttempt() {
        assertThat(store.attempts()).hasSize(1);
        return store.attempts().getFirst();
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @ParameterizedTest(name = "opt-in={0}")
    @ValueSource(booleans = {false, true})
    void anAcceptedOtpReachesTheServerOnlyAndNeverTheConsole(boolean optIn) throws Exception {
        ses = SesSmtpTestKit.accepting();
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        Notification notification = otp();

        ClaimedSchedule claim = dispatch(notification, optIn, console);

        assertThat(ses.acceptedMessages()).isEqualTo(1);
        verify(console, never()).send(any(), any());
        assertThat(singleAttempt().getStatus()).isEqualTo(AttemptStatus.ACCEPTED);
        assertThat(singleAttempt().getProviderName()).isEqualTo("ses");
        assertThat(singleAttempt().getProviderMessageId()).isNotBlank();
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.ACCEPTED);
        assertThat(store.schedule(claim.scheduleId()).getStatus()).isEqualTo(ScheduleStatus.SENT);
        assertThat(logs()).noneMatch(line -> line.contains(SYNTHETIC_CODE));
    }

    @ParameterizedTest(name = "opt-in={0}")
    @ValueSource(booleans = {false, true})
    void aLostFinalReplyIsNeitherRetriedNorFallsBackToTheConsole(boolean optIn) throws Exception {
        ses = SesSmtpTestKit.losingFinalReply();
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        Notification notification = otp();

        ClaimedSchedule claim = dispatch(notification, optIn, console);

        assertThat(ses.connections()).isEqualTo(1);
        verify(console, never()).send(any(), any());

        NotificationAttempt attempt = singleAttempt();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.UNKNOWN);
        assertThat(attempt.getErrorCode()).isEqualTo(NotificationDispatcherService.ACCEPTANCE_UNKNOWN);
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(store.schedule(claim.scheduleId()).getNextRetryAt()).isNull();
        assertThat(logs()).noneMatch(line -> line.contains(SYNTHETIC_CODE));
    }

    @Test
    void aTemporaryRejectionFollowsTheExistingRetryPolicy() throws Exception {
        ses = SesSmtpTestKit.throttling();
        Notification notification = otp();

        ClaimedSchedule claim = dispatch(notification, false, new ConsoleNotificationProvider(false));

        NotificationAttempt attempt = singleAttempt();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(attempt.getFailureType()).isEqualTo(FailureType.TEMPORARY);
        assertThat(attempt.getErrorCode()).isEqualTo("SES_SMTP_FINAL_REPLY_454");
        assertThat(store.schedule(claim.scheduleId()).getNextRetryAt()).isNotNull();
    }

    @ParameterizedTest(name = "opt-in={0}")
    @ValueSource(booleans = {false, true})
    void aRejectedReminderDoesNotLeakItsContentThroughTheConsole(boolean optIn) throws Exception {
        ses = SesSmtpTestKit.rejectingRecipient();
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        Notification notification = appointment();

        dispatch(notification, optIn, console);

        verify(console, times(1)).send(any(), any());
        assertThat(store.attempts()).extracting(NotificationAttempt::getChannel)
                .containsExactly(NotificationChannel.EMAIL, NotificationChannel.CONSOLE);
        assertThat(store.attempts().get(1).getErrorCode())
                .isEqualTo(ConsoleNotificationProvider.DIAGNOSTIC_ONLY_ERROR_CODE);
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(logs()).noneMatch(line -> line.contains(PATIENT));
    }

    @Test
    void withoutARealProviderAReminderIsMaskedOnTheConsoleAndStaysDiagnostic() {
        ConsoleNotificationProvider console = new ConsoleNotificationProvider(false);
        Notification notification = appointment();
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);

        dispatcher(store, clock, false, console).dispatch(claim);

        assertThat(logs()).anyMatch(line -> line.contains("[contenido sensible oculto]"));
        assertThat(logs()).noneMatch(line -> line.contains(PATIENT));
        assertThat(singleAttempt().getErrorCode()).isEqualTo(ConsoleNotificationProvider.DIAGNOSTIC_ONLY_ERROR_CODE);
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void withoutARealProviderTheSyntheticOptInShowsAReminderAndItStaysDiagnostic() {
        ConsoleNotificationProvider console = new ConsoleNotificationProvider(true);
        Notification notification = appointment();
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);

        dispatcher(store, clock, true, console).dispatch(claim);

        assertThat(logs()).anyMatch(line -> line.startsWith("[CONSOLE_DIAGNOSTIC]") && line.contains(PATIENT));
        assertThat(singleAttempt().getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(singleAttempt().getAcceptedAt()).isNull();
    }
}
