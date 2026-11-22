package co.edu.unicauca.piedrazul.backend.notifications.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.MutableClock;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.StubProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.ClaimedSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Despacho de los avisos de cancelación y reagendamiento: solo correo (entrega real) y consola
 * (diagnóstico), sin exigir que la cita siga AGENDADA, y sin datos de la cita en consola ni en
 * logs. Datos exclusivamente sintéticos.
 */
class AppointmentChangeNoticeDispatchTest {

    private static final String PATIENT = "Paciente Sintético";
    private static final String DOCTOR = "Dra. Sintética";
    private static final String EMAIL = "sintetico@example.test";

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

    @ParameterizedTest
    @EnumSource(value = NotificationType.class, names = {"APPOINTMENT_CANCELLED", "APPOINTMENT_RESCHEDULED"})
    void onlyEmailAndDiagnosticConsoleAreOfferedAndTheTypesAreIdentifiable(NotificationType type) {
        assertThat(new co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy()
                .resolveChannels(type)).containsExactly(NotificationChannel.EMAIL, NotificationChannel.CONSOLE);
        assertThat(type.containsIdentifiableContent()).isTrue();
        assertThat(type.requiresScheduledAppointment()).isFalse();
        assertThat(type.isRetired()).isFalse();
    }

    @ParameterizedTest(name = "{0}, opt-in={1}")
    @org.junit.jupiter.params.provider.MethodSource("typesByOptIn")
    void withoutARealProviderTheConsoleIsDiagnosticOnlyAndLeaksNothing(NotificationType type, boolean optIn) {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        Notification notification = notice(type, EMAIL);

        dispatch(notification, optIn, console);

        verify(console, times(1)).send(any(), any());
        NotificationAttempt attempt = singleAttempt();
        assertThat(attempt.getChannel()).isEqualTo(NotificationChannel.CONSOLE);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo(ConsoleNotificationProvider.DIAGNOSTIC_ONLY_ERROR_CODE);
        assertThat(attempt.getDeliveredAt()).isNull();
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertNoPersonalDataLogged();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class, names = {"APPOINTMENT_CANCELLED", "APPOINTMENT_RESCHEDULED"})
    void aRealProviderDeliversByEmailOnlyEvenThoughTheAppointmentIsNoLongerAgendada(NotificationType type) {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(true));
        StubProvider email = StubProvider.email(() -> result(AttemptStatus.ACCEPTED, null));
        Notification notification = notice(type, EMAIL);
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);

        // La cita ya no está AGENDADA (eligibilidad falsa): el aviso se entrega igual
        dispatcher(store, clock, appointmentId -> false, email, console).dispatch(claim);

        assertThat(email.calls()).isEqualTo(1);
        verify(console, never()).send(any(), any());
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.ACCEPTED);
        assertThat(singleAttempt().getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertNoPersonalDataLogged();
    }

    @ParameterizedTest(name = "{0}, opt-in={1}")
    @org.junit.jupiter.params.provider.MethodSource("typesByOptIn")
    void aFailingRealProviderFallsBackToConsoleWithoutTheContentAndNeverCountsAsDelivered(
            NotificationType type, boolean optIn) {
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(optIn));
        StubProvider email = StubProvider.email(() -> result(AttemptStatus.FAILED, FailureType.PERMANENT));
        Notification notification = notice(type, EMAIL);

        dispatch(notification, optIn, email, console);

        verify(console, times(1)).send(any(), any());
        assertThat(store.notification(notification.getId()).getStatus())
                .isEqualTo(NotificationStatus.FAILED)
                .isNotEqualTo(NotificationStatus.DELIVERED);
        assertThat(store.attempts()).extracting(NotificationAttempt::getChannel)
                .containsOnly(NotificationChannel.EMAIL, NotificationChannel.CONSOLE);
        assertNoPersonalDataLogged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"paciente@migracion.invalid", "no es un correo", ""})
    void anUnusableEmailNeverReachesTheProviderAndEndsAsDiagnosticFailure(String address) {
        StubProvider email = StubProvider.email(() -> result(AttemptStatus.ACCEPTED, null));
        ConsoleNotificationProvider console = spy(new ConsoleNotificationProvider(false));
        Notification notification = notice(NotificationType.APPOINTMENT_RESCHEDULED, address);

        dispatch(notification, false, email, console);

        assertThat(email.calls()).isZero();
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertNoPersonalDataLogged();
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> typesByOptIn() {
        return java.util.stream.Stream.of(NotificationType.APPOINTMENT_CANCELLED, NotificationType.APPOINTMENT_RESCHEDULED)
                .flatMap(type -> java.util.stream.Stream.of(false, true)
                        .map(optIn -> org.junit.jupiter.params.provider.Arguments.of(type, optIn)));
    }

    private static Notification notice(NotificationType type, String email) {
        Map<String, String> variables = Map.of(
                "patientName", PATIENT, "doctorName", DOCTOR, "previousDoctorName", DOCTOR,
                "date", "Sábado 10 de enero de 2026", "time", "8:00 a. m.", "specialty", "Fisioterapia",
                "previousDate", "Viernes 9 de enero de 2026", "previousTime", "9:00 a. m.",
                "previousSpecialty", "Fisioterapia");
        return notification(type, AggregateType.APPOINTMENT, email, variables);
    }

    private ClaimedSchedule dispatch(Notification notification, boolean optIn, NotificationProvider... providers) {
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
        dispatcher(store, clock, optIn, providers).dispatch(claim);
        return claim;
    }

    private NotificationAttempt singleAttempt() {
        List<NotificationAttempt> attempts = store.attempts();
        assertThat(attempts).hasSize(1);
        return attempts.get(0);
    }

    private void assertNoPersonalDataLogged() {
        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .noneMatch(line -> line.contains(PATIENT) || line.contains(DOCTOR)
                        || line.contains("enero") || line.contains(EMAIL));
    }
}
