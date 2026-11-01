package co.edu.unicauca.piedrazul.backend.notifications.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.appointment.eligibility.AppointmentEligibility;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.MutableClock;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.StubProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.exception.InvalidNotificationStateTransitionException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.exception.InvalidScheduleStateTransitionException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.ClaimedSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DispatchFinish;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DispatchWrite;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Orden y contabilidad de las lecturas de elegibilidad, y cierres sobre fallos forzados. La garantía
 * sobre el estado confirmado se demuestra en {@code AppointmentReminderEligibilityIT}, contra la
 * base real; aquí las respuestas son guionizadas. Datos exclusivamente sintéticos.
 */
class NotificationDispatcherEligibilityTest {

    private final InMemoryDispatchStore store = new InMemoryDispatchStore();
    private final MutableClock clock = new MutableClock(NOW);
    private final ScriptedEligibility eligibility = new ScriptedEligibility();

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

    // ── Una lectura propia por entrega ──────────────────────────────────

    @Nested
    class PerHandoffReads {

        @Test
        void handoffReadHappensAfterTheProcessingAttemptIsConfirmedAndBeforeTheProvider() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            List<String> observed = new ArrayList<>();
            eligibility.onRead = () -> observed.add(store.attempts().stream()
                    .map(attempt -> attempt.getStatus().name()).toList() + " llamadas=" + email.calls());
            Notification notification = appointment();

            run(notification, email);

            assertThat(observed).containsExactly("[] llamadas=0", "[PROCESSING] llamadas=0");
            assertThat(email.calls()).isEqualTo(1);
            assertStates(notification, NotificationStatus.ACCEPTED, ScheduleStatus.SENT);
        }

        @Test
        void eachRetryNeedsItsOwnFreshReads() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.RETRYABLE))
                    .then(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();

            ClaimedSchedule first = run(notification, email);
            assertThat(eligibility.reads).isEqualTo(2);

            redispatch(first, email);

            assertThat(eligibility.reads).isEqualTo(4);
            assertThat(email.calls()).isEqualTo(2);
            assertStates(notification, NotificationStatus.ACCEPTED, ScheduleStatus.SENT);
        }

        @Test
        void eachFallbackHandoffNeedsItsOwnFreshRead() {
            StubProvider sms = new StubProvider(NotificationChannel.SMS, false).then(() -> outcome(DeliveryOutcome.REJECTED));
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();

            run(notification, sms, email);

            // Inicial, entrega por SMS, antes de abrir el canal siguiente y entrega por correo.
            assertThat(eligibility.reads).isEqualTo(4);
            assertThat(sms.calls()).isEqualTo(1);
            assertThat(email.calls()).isEqualTo(1);
        }

        @Test
        void otpNeverReadsAppointmentEligibility() {
            eligibility.script(() -> {
                throw new AssertionError("Un código no depende de ninguna cita");
            });
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = otp();

            run(notification, email);

            assertThat(eligibility.reads).isZero();
            assertThat(email.calls()).isEqualTo(1);
        }
    }

    // ── Cierre por inelegibilidad ───────────────────────────────────────

    @Nested
    class Cancellation {

        @Test
        void ineligibleBeforeStartingClosesWithoutAnAttempt() {
            eligibility.script(() -> false);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();

            run(notification, email);

            assertThat(eligibility.reads).isEqualTo(1);
            assertThat(email.calls()).isZero();
            assertThat(store.attempts()).isEmpty();
            assertStates(notification, NotificationStatus.CANCELLED, ScheduleStatus.CANCELLED);
        }

        @Test
        void ineligibleAtHandoffCancelsTheAttemptItOpened() {
            eligibility.script(() -> true, () -> false);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();

            run(notification, email);

            assertThat(email.calls()).isZero();
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.CANCELLED);
            assertThat(store.attempts().getFirst().getFailureType()).isNull();
            assertStates(notification, NotificationStatus.CANCELLED, ScheduleStatus.CANCELLED);
        }

        @Test
        void cancellationAfterARetryKeepsTheEarlierAttempt() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.RETRYABLE));
            Notification notification = appointment();
            ClaimedSchedule first = run(notification, email);

            eligibility.script(() -> false);
            redispatch(first, email);

            assertThat(email.calls()).isEqualTo(1);
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.FAILED);
            assertStates(notification, NotificationStatus.CANCELLED, ScheduleStatus.CANCELLED);
        }

        @Test
        void cancellationBetweenChannelsOpensNoFurtherAttempt() {
            eligibility.script(() -> true, () -> true, () -> false);
            StubProvider sms = new StubProvider(NotificationChannel.SMS, false).then(() -> outcome(DeliveryOutcome.REJECTED));
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();

            run(notification, sms, email);

            assertThat(sms.calls()).isEqualTo(1);
            assertThat(email.calls()).isZero();
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.FAILED);
            assertStates(notification, NotificationStatus.CANCELLED, ScheduleStatus.CANCELLED);
        }

        @Test
        void cancellationAtTheFallbackHandoffCancelsOnlyThatAttempt() {
            eligibility.script(() -> true, () -> true, () -> true, () -> false);
            StubProvider sms = new StubProvider(NotificationChannel.SMS, false).then(() -> outcome(DeliveryOutcome.REJECTED));
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();

            run(notification, sms, email);

            assertThat(email.calls()).isZero();
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.FAILED, AttemptStatus.CANCELLED);
            assertStates(notification, NotificationStatus.CANCELLED, ScheduleStatus.CANCELLED);
        }

        @Test
        void cancellationAfterTheHandoffDoesNotChangeTheProviderResult() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            email.onSend = () -> eligibility.script(() -> false);
            Notification notification = appointment();

            run(notification, email);

            assertThat(attemptStatuses()).containsExactly(AttemptStatus.ACCEPTED);
            assertStates(notification, NotificationStatus.ACCEPTED, ScheduleStatus.SENT);
        }

        @Test
        void lostOwnershipAtTheCancellationStillRecordsTheOwnAttempt() {
            eligibility.script(() -> true, () -> false);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            store.beforeWrite = write -> {
                if (write.finish() == DispatchFinish.CANCELLED) {
                    store.forceScheduleStatus(write.scheduleId(), ScheduleStatus.FAILED, NOW);
                }
            };

            run(notification, email);

            assertThat(email.calls()).isZero();
            // El intento no llegó al proveedor; notificación y schedule son del otro actor.
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.CANCELLED);
            assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        }
    }

    // ── Precedencia del trabajo interrumpido ────────────────────────────

    @Nested
    class InterruptedFirst {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = AttemptStatus.class, names = {"PROCESSING", "UNKNOWN", "ACCEPTED"})
        void possibleAcceptanceIsResolvedWithoutConsultingTheAppointment(AttemptStatus history) {
            eligibility.script(() -> false);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            ClaimedSchedule claim = claimWithHistory(notification, history);

            dispatcher(store, clock, eligibility, email).dispatch(claim);

            assertThat(eligibility.reads).isZero();
            assertThat(email.calls()).isZero();

            if (history == AttemptStatus.ACCEPTED) {
                assertThat(attemptStatuses()).containsExactly(AttemptStatus.ACCEPTED);
                assertStates(notification, NotificationStatus.ACCEPTED, ScheduleStatus.SENT);
            } else {
                assertThat(attemptStatuses()).containsExactly(AttemptStatus.UNKNOWN);
                assertStates(notification, NotificationStatus.FAILED, ScheduleStatus.FAILED);
            }
        }
    }

    // ── Fallos de la lectura y del cierre ───────────────────────────────

    @Nested
    class Failures {

        @Test
        void failedInitialReadClosesAsFailedWithoutAttempts() {
            eligibility.script(ScriptedEligibility::fail);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();

            run(notification, email);

            assertThat(email.calls()).isZero();
            assertThat(store.attempts()).isEmpty();
            assertStates(notification, NotificationStatus.FAILED, ScheduleStatus.FAILED);
            assertSafeDiagnostic();
        }

        @Test
        void failedHandoffReadIsAProvenFailureBeforeTheProvider() {
            eligibility.script(() -> true, ScriptedEligibility::fail);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();

            run(notification, email);

            assertThat(email.calls()).isZero();
            NotificationAttempt attempt = store.attempts().getFirst();
            assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
            assertThat(attempt.getFailureType()).isEqualTo(FailureType.PERMANENT);
            assertThat(attempt.getErrorCode()).isEqualTo(NotificationDispatcherService.DISPATCH_INTERRUPTED);
            assertThat(attempt.mayHaveBeenAccepted()).isFalse();
            assertStates(notification, NotificationStatus.FAILED, ScheduleStatus.FAILED);
            assertSafeDiagnostic();
        }

        @Test
        void failedReadBetweenChannelsInvokesNoFurtherProvider() {
            eligibility.script(() -> true, () -> true, ScriptedEligibility::fail);
            StubProvider sms = new StubProvider(NotificationChannel.SMS, false).then(() -> outcome(DeliveryOutcome.REJECTED));
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();

            run(notification, sms, email);

            assertThat(email.calls()).isZero();
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.FAILED);
            assertStates(notification, NotificationStatus.FAILED, ScheduleStatus.FAILED);
        }

        @Test
        void failedCancellationWriteIsRetriedAsACancellation() {
            eligibility.script(() -> true, () -> false);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            failFirstCancellationWrite();

            run(notification, email);

            assertThat(email.calls()).isZero();
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.CANCELLED);
            assertStates(notification, NotificationStatus.CANCELLED, ScheduleStatus.CANCELLED);
        }

        @Test
        void failedCancellationWithoutAttemptIsRetriedAsACancellation() {
            eligibility.script(() -> false);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            failFirstCancellationWrite();

            run(notification, email);

            assertThat(store.attempts()).isEmpty();
            assertStates(notification, NotificationStatus.CANCELLED, ScheduleStatus.CANCELLED);
        }

        /** Riesgo aceptado: sobrestima la incertidumbre, pero nunca reenvía. */
        @Test
        void doubleFaultIsResolvedConservativelyAsUnknownByRecovery() {
            eligibility.script(() -> true, ScriptedEligibility::fail);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            store.beforeWrite = write -> {
                if (write.finish() == DispatchFinish.FAILED) {
                    throw new IllegalStateException("base sintética caída");
                }
            };

            ClaimedSchedule claim = run(notification, email);

            assertThat(email.calls()).isZero();
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.PROCESSING);

            clock.advance(Duration.ofHours(1));
            store.recover(claim.scheduleId(), clock.instant(), clock.instant());

            assertThat(email.calls()).isZero();
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.UNKNOWN);
            assertStates(notification, NotificationStatus.FAILED, ScheduleStatus.FAILED);
        }

        private void failFirstCancellationWrite() {
            store.beforeWrite = new Consumer<>() {
                private boolean failed;

                @Override
                public void accept(DispatchWrite write) {
                    if (write.finish() == DispatchFinish.CANCELLED && !failed) {
                        failed = true;
                        throw new IllegalStateException("base sintética caída");
                    }
                }
            };
        }

        private void assertSafeDiagnostic() {
            assertThat(logs()).anyMatch(line -> line.startsWith("NOTIFICATION_DISPATCH_INTERRUPTED")
                    && line.contains("stage=eligibility"));
            assertThat(logs()).noneMatch(line -> line.contains(ScriptedEligibility.INTERNAL_DETAIL));
        }
    }

    // ── Confirmaciones históricas ───────────────────────────────────────

    @Nested
    class RetiredConfirmation {

        @Test
        void queuedConfirmationClosesWithoutProviderNorAppointmentRead() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = legacyConfirmation();

            run(notification, email);

            assertThat(email.calls()).isZero();
            assertThat(eligibility.reads).isZero();
            assertThat(store.attempts()).isEmpty();
            assertStates(notification, NotificationStatus.CANCELLED, ScheduleStatus.CANCELLED);
        }

        @Test
        void confirmationWaitingForARetryKeepsItsEarlierAttempt() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = legacyConfirmation();
            ClaimedSchedule claim = claimWithHistory(notification, AttemptStatus.FAILED);

            dispatcher(store, clock, eligibility, email).dispatch(claim);

            assertThat(email.calls()).isZero();
            assertThat(attemptStatuses()).containsExactly(AttemptStatus.FAILED);
            assertStates(notification, NotificationStatus.CANCELLED, ScheduleStatus.CANCELLED);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = AttemptStatus.class, names = {"UNKNOWN", "ACCEPTED"})
        void possibleAcceptanceOfAConfirmationIsNotRewritten(AttemptStatus history) {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = legacyConfirmation();
            ClaimedSchedule claim = claimWithHistory(notification, history);

            dispatcher(store, clock, eligibility, email).dispatch(claim);

            assertThat(email.calls()).isZero();
            assertThat(attemptStatuses()).containsExactly(history);
            assertThat(store.notification(notification.getId()).getStatus())
                    .isEqualTo(history == AttemptStatus.ACCEPTED ? NotificationStatus.ACCEPTED : NotificationStatus.FAILED);
        }
    }

    // ── Transición en el modelo ─────────────────────────────────────────

    @Nested
    class Transition {

        @Test
        void cancellationEndsTheDispatch() {
            assertThat(DispatchFinish.CANCELLED.endsDispatch()).isTrue();
        }

        @Test
        void acceptedNotificationCannotBeCancelled() {
            Notification accepted = appointment();
            accepted.markProcessing(NOW);
            accepted.markAccepted(NOW);
            NotificationSchedule schedule = NotificationSchedule.create(accepted.getId(), NOW, NOW);
            schedule.markProcessing(NOW);

            assertThatThrownBy(() -> DispatchFinish.CANCELLED.applyTo(accepted, schedule, null, NOW))
                    .isInstanceOf(InvalidNotificationStateTransitionException.class);
        }

        @Test
        void sentScheduleCannotBeCancelled() {
            Notification processing = appointment();
            processing.markProcessing(NOW);
            NotificationSchedule sent = NotificationSchedule.create(processing.getId(), NOW, NOW);
            sent.markProcessing(NOW);
            sent.markSent(NOW);

            assertThatThrownBy(() -> DispatchFinish.CANCELLED.applyTo(processing, sent, null, NOW))
                    .isInstanceOf(InvalidScheduleStateTransitionException.class);
        }

        @Test
        void onlyTheReminderRequiresAScheduledAppointment() {
            assertThat(NotificationType.APPOINTMENT_REMINDER_2_DAYS.requiresScheduledAppointment()).isTrue();
            assertThat(NotificationType.APPOINTMENT_SCHEDULED.requiresScheduledAppointment()).isFalse();
            assertThat(NotificationType.OTP_CODE.requiresScheduledAppointment()).isFalse();
        }

        @Test
        void onlyTheConfirmationIsRetired() {
            assertThat(NotificationType.APPOINTMENT_SCHEDULED.isRetired()).isTrue();
            assertThat(NotificationType.APPOINTMENT_REMINDER_2_DAYS.isRetired()).isFalse();
            assertThat(NotificationType.OTP_CODE.isRetired()).isFalse();
        }
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private ClaimedSchedule run(Notification notification, NotificationProvider... providers) {
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
        dispatcher(store, clock, eligibility, providers).dispatch(claim);
        return claim;
    }

    private void redispatch(ClaimedSchedule claim, NotificationProvider... providers) {
        dispatcher(store, clock, eligibility, providers).dispatch(store.reclaim(claim.scheduleId(), NOW));
    }

    /** Trabajo reclamado con un intento previo del estado indicado. */
    private ClaimedSchedule claimWithHistory(Notification notification, AttemptStatus history) {
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
        NotificationAttempt attempt = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                NotificationChannel.EMAIL, "stub-email", 1, NOW);
        attempt.markProcessing(NOW);

        switch (history) {
            case PROCESSING -> {
            }
            case ACCEPTED -> attempt.markAccepted("stub-message", NOW);
            case UNKNOWN -> attempt.markAcceptanceUnknown(NotificationDispatcherService.ACCEPTANCE_UNKNOWN, null, NOW);
            case FAILED -> attempt.markFailed(FailureType.TEMPORARY, "SYNTHETIC", null, NOW);
            default -> throw new IllegalArgumentException("Estado no soportado: " + history);
        }

        store.putAttempt(attempt);
        return new ClaimedSchedule(claim.scheduleId(), 1);
    }

    private List<AttemptStatus> attemptStatuses() {
        return store.attempts().stream().map(NotificationAttempt::getStatus).toList();
    }

    private void assertStates(Notification notification, NotificationStatus status, ScheduleStatus scheduleStatus) {
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(status);
        assertThat(store.schedule(store.lastScheduleFor(notification.getId())).getStatus()).isEqualTo(scheduleStatus);
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Respuestas en orden; el último comportamiento se repite. Por defecto, cita agendada. */
    private static final class ScriptedEligibility implements AppointmentEligibility {
        private static final String INTERNAL_DETAIL = "detalle interno de la base";

        private final Deque<BooleanSupplier> answers = new ArrayDeque<>(List.of(() -> true));
        private int reads;
        private Runnable onRead = () -> {
        };

        private void script(BooleanSupplier... scripted) {
            answers.clear();
            answers.addAll(List.of(scripted));
        }

        private static boolean fail() {
            throw new IllegalStateException(INTERNAL_DETAIL);
        }

        @Override
        public boolean isScheduled(UUID appointmentId) {
            reads++;
            onRead.run();
            BooleanSupplier answer = answers.size() > 1 ? answers.pollFirst() : answers.peekFirst();
            return answer.getAsBoolean();
        }
    }
}
