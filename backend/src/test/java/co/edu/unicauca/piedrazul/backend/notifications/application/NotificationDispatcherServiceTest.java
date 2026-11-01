package co.edu.unicauca.piedrazul.backend.notifications.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.MutableClock;
import co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.StubProvider;
import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationDispatchException;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.*;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contrato de resultados (IC1), reintentos, cambios de canal y transiciones del despachador.
 * Datos exclusivamente sintéticos.
 */
class NotificationDispatcherServiceTest {

    private final InMemoryDispatchStore store = new InMemoryDispatchStore();
    private final MutableClock clock = new MutableClock(NOW);
    private final ConsoleNotificationProvider console = new ConsoleNotificationProvider(false);

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

    // ── IC1: resultados del proveedor ───────────────────────────────────

    @Nested
    class Outcomes {

        @Test
        void acceptedEndsDispatchWithoutFallbackAndRedactsCode() {
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = otp();

            ClaimedSchedule claim = run(notification, email);

            assertThat(email.calls()).isEqualTo(1);
            NotificationAttempt attempt = single();
            assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.ACCEPTED);
            assertThat(attempt.getAcceptedAt()).isNotNull();
            assertThat(attempt.getDeliveredAt()).isNull();
            assertNotification(notification, NotificationStatus.ACCEPTED);
            assertSchedule(claim, ScheduleStatus.SENT);
            assertCodeRedacted(notification);
        }

        @Test
        void acceptedNonSensitiveNeverFallsBackToConsole() {
            ConsoleCounter consoleCounter = new ConsoleCounter();
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));

            run(appointment(), email, consoleCounter);

            assertThat(consoleCounter.calls).isZero();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = AttemptStatus.class, names = {"DELIVERED", "READ", "SENT"})
        void synchronousDeliveryClaimsAreRecordedOnlyAsAccepted(AttemptStatus claimed) {
            StubProvider email = StubProvider.email(() -> result(claimed, null));
            Notification notification = appointment();

            run(notification, email);

            assertThat(single().getStatus()).isEqualTo(AttemptStatus.ACCEPTED);
            assertThat(single().getDeliveredAt()).isNull();
            assertNotification(notification, NotificationStatus.ACCEPTED);
        }

        @Test
        void unknownIsRecordedAtAttemptLevelWithoutRetryOrFallback() {
            ConsoleCounter consoleCounter = new ConsoleCounter();
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.UNKNOWN));
            Notification notification = appointment();

            ClaimedSchedule claim = run(notification, email, consoleCounter);

            assertAcceptanceUnknown(notification, claim);
            assertThat(consoleCounter.calls).isZero();
            assertThat(email.calls()).isEqualTo(1);
        }

        @Test
        void unclassifiedProviderExceptionIsAcceptanceUnknown() {
            StubProvider email = StubProvider.qualifiedEmail(() -> {
                throw new IllegalStateException("fallo sintético con " + SYNTHETIC_CODE);
            });
            Notification notification = otp();

            ClaimedSchedule claim = run(notification, email);

            assertAcceptanceUnknown(notification, claim);
            assertCodeRedacted(notification);
            // El mensaje de la excepción no se guarda ni se registra.
            assertThat(single().getErrorMessage()).doesNotContain(SYNTHETIC_CODE);
            assertThat(logs()).noneMatch(line -> line.contains(SYNTHETIC_CODE));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"PROVIDER_IO_ERROR", "PROVIDER_503", "PROVIDER_429", "INVALID_EMAIL_MESSAGE"})
        void dispatchExceptionWithoutEvidenceIsAcceptanceUnknown(String errorCode) {
            // Una excepción sin evidencia de no aceptación se trata con cautela.
            StubProvider email = StubProvider.email(() -> {
                throw new NotificationDispatchException(errorCode, "sintético");
            });
            Notification notification = appointment();

            ClaimedSchedule claim = run(notification, email);

            assertAcceptanceUnknown(notification, claim);
            assertThat(single().getErrorMessage()).contains(errorCode);
        }

        @Test
        void retryableSchedulesBoundedRetryAndKeepsCode() {
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.RETRYABLE));
            Notification notification = otp();

            ClaimedSchedule claim = run(notification, email);

            NotificationAttempt attempt = single();
            assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
            assertThat(attempt.getFailureType()).isEqualTo(FailureType.TEMPORARY);
            assertNotification(notification, NotificationStatus.PROCESSING);
            NotificationSchedule schedule = store.schedule(claim.scheduleId());
            assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.PENDING);
            assertThat(schedule.getNextRetryAt()).isEqualTo(NOW.plusSeconds(60));
            assertThat(store.notification(notification.getId()).getVariables())
                    .containsEntry(NotificationVariables.CODE, SYNTHETIC_CODE);
        }

        @Test
        void retryableExceptionWithExplicitEvidenceIsRetried() {
            StubProvider email = StubProvider.email(() -> {
                throw new NotificationDispatchException("SYNTHETIC_THROTTLED", "sintético",
                        DeliveryOutcome.RETRYABLE, null);
            });

            ClaimedSchedule claim = run(appointment(), email);

            assertThat(single().getFailureType()).isEqualTo(FailureType.TEMPORARY);
            assertSchedule(claim, ScheduleStatus.PENDING);
        }

        @Test
        void retriesStopAtPolicyLimitThenFallBack() {
            ConsoleCounter consoleCounter = new ConsoleCounter();
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.RETRYABLE));
            Notification notification = appointment();
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
            NotificationDispatcherService dispatcher = dispatcher(store, clock, false, email, consoleCounter);

            claim = runUntilSettled(dispatcher, claim);

            // EMAIL: inicial + 2 reintentos; después la consola (último canal), sin más reintentos.
            assertThat(email.calls()).isEqualTo(3);
            assertThat(consoleCounter.calls).isEqualTo(1);
            assertThat(store.attempts()).extracting(NotificationAttempt::getAttemptNumber).containsExactly(1, 2, 3, 1);
            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
        }

        @Test
        void circuitOpenIsRetryableAndNotAnAmbiguousAcceptance() {
            StubProvider email = StubProvider.email(() -> {
                throw new NotificationDispatchException(NotificationDispatchException.CIRCUIT_OPEN, "sintético");
            });

            ClaimedSchedule claim = run(appointment(), email);

            NotificationAttempt attempt = single();
            assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
            assertThat(attempt.getFailureType()).isEqualTo(FailureType.CIRCUIT_OPEN);
            assertThat(attempt.getErrorCode()).isEqualTo(NotificationDispatchException.CIRCUIT_OPEN);
            assertSchedule(claim, ScheduleStatus.PENDING);
            assertThat(logs()).noneMatch(line -> line.contains("NOTIFICATION_ACCEPTANCE_UNKNOWN"));
        }

        @Test
        void rejectedIsNotRetriedAndMayFallBackToAnEligibleChannel() {
            ConsoleCounter consoleCounter = new ConsoleCounter();
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.REJECTED));
            Notification notification = appointment();

            ClaimedSchedule claim = run(notification, email, consoleCounter);

            assertThat(email.calls()).isEqualTo(1);
            assertThat(consoleCounter.calls).isEqualTo(1);
            assertThat(store.attempts().get(0).getFailureType()).isEqualTo(FailureType.PERMANENT);
            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
            assertThat(store.schedule(claim.scheduleId()).getNextRetryAt()).isNull();
        }

        @Test
        void rejectedOtpFailsAndRedactsCode() {
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.REJECTED));
            Notification notification = otp();

            ClaimedSchedule claim = run(notification, email);

            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
            assertCodeRedacted(notification);
        }

        @Test
        void providerErrorIsNotRetriedAndKeepsDiagnosticCode() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.PROVIDER_ERROR));
            Notification notification = appointment();

            ClaimedSchedule claim = run(notification, email);

            NotificationAttempt attempt = single();
            assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
            assertThat(attempt.getFailureType()).isEqualTo(FailureType.PERMANENT);
            assertThat(attempt.getErrorCode()).isEqualTo("SYNTHETIC_CONFIG");
            assertSchedule(claim, ScheduleStatus.FAILED);
            assertThat(store.schedule(claim.scheduleId()).getNextRetryAt()).isNull();
        }

        @Test
        void rejectedChannelIsNotRetriedWhenAnotherChannelSchedulesARetry() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.REJECTED));
            StubProvider sms = new StubProvider(NotificationChannel.SMS, false)
                    .then(() -> outcome(DeliveryOutcome.RETRYABLE))
                    .then(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = notification(NotificationType.APPOINTMENT_REMINDER_2_DAYS, AggregateType.APPOINTMENT,
                    "sintetico@example.test", Map.of("patientName", "P", "doctorName", "D", "date", "d", "time", "t"));
            // Orden propio: EMAIL antes que SMS
            notification = Notification.reconstruct(notification.getId(), notification.getType(),
                    notification.getAggregate(), notification.getRecipient(),
                    new ChannelPreference(List.of(NotificationChannel.EMAIL, NotificationChannel.SMS)),
                    notification.getVariables(), notification.getIdempotencyKey(), notification.getStatus(),
                    notification.getCreatedAt(), notification.getUpdatedAt());
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);

            runUntilSettled(dispatcher(store, clock, false, email, sms), claim);

            assertThat(email.calls()).isEqualTo(1);
            assertThat(sms.calls()).isEqualTo(2);
            assertNotification(notification, NotificationStatus.ACCEPTED);
        }
    }

    // ── Fallos locales: antes del proveedor ─────────────────────────────

    @Nested
    class LocalFailures {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {
                "", " ", "no-es-correo", "a@b@c", "sintetico@example.invalid", "sintetico@EXAMPLE.INVALID",
                "sintetico@correo.example.invalid", "sintetico@example.invalid.", "sintetico@Invalid"
        })
        void unusableEmailNeverReachesProviderNorItsCircuitBreaker(String email) {
            CircuitBreaker breaker = CircuitBreaker.ofDefaults("sintetico");
            BreakerProvider provider = new BreakerProvider(breaker);
            Notification notification = otp(email, NOW.plus(Duration.ofMinutes(5)));

            ClaimedSchedule claim = run(notification, provider);

            assertThat(provider.calls).isZero();
            assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
            NotificationAttempt attempt = single();
            assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
            assertThat(attempt.getFailureType()).isEqualTo(FailureType.PERMANENT);
            assertThat(attempt.getErrorCode()).startsWith("RECIPIENT_EMAIL_");
            assertNeverProcessing();
            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
            assertCodeRedacted(notification);
        }

        @Test
        void missingEmailIsDistinguishedFromInvalidEmail() {
            run(otp(null, NOW.plus(Duration.ofMinutes(5))), StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED)));

            assertThat(single().getErrorCode()).isEqualTo("RECIPIENT_EMAIL_MISSING");
        }

        @Test
        void renderingFailureIsLocalAndNeverUnknown() {
            TemplateRenderer failing = new TemplateRenderer(new InMemoryNotificationTemplateProvider()) {
                @Override
                public ChannelMessage render(Notification notification, NotificationChannel channel, Map<String, String> variables) {
                    throw new IllegalStateException("plantilla sintética rota");
                }
            };
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = otp();
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);

            dispatcher(store, clock, false, failing, email).dispatch(claim);

            assertThat(email.calls()).isZero();
            assertThat(single().getStatus()).isEqualTo(AttemptStatus.FAILED);
            assertThat(single().getErrorCode()).isEqualTo("TEMPLATE_RENDERING_ERROR");
            assertNeverProcessing();
            assertNotification(notification, NotificationStatus.FAILED);
            assertThat(logs()).noneMatch(line -> line.contains("NOTIFICATION_ACCEPTANCE_UNKNOWN"));
        }

        @Test
        void incompleteContentIsNotSent() {
            // Sin la variable del código, la plantilla quedaría con el marcador sin resolver.
            Notification notification = notification(NotificationType.OTP_CODE, AggregateType.VERIFICATION,
                    "sintetico@example.test", Map.of("expirationMinutes", "5",
                            NotificationVariables.EXPIRES_AT, NOW.plus(Duration.ofMinutes(5)).toString()));
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED));

            run(notification, email);

            assertThat(email.calls()).isZero();
            assertThat(single().getErrorCode()).isEqualTo("CONTENT_INVALID");
        }

        @Test
        void localFailureFallsBackCoherentlyToTheNextChannel() {
            ConsoleCounter consoleCounter = new ConsoleCounter();
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment("sintetico@example.invalid");

            ClaimedSchedule claim = run(notification, email, consoleCounter);

            assertThat(email.calls()).isZero();
            assertThat(consoleCounter.calls).isEqualTo(1);
            assertThat(store.attempts()).extracting(NotificationAttempt::getChannel)
                    .containsExactly(NotificationChannel.EMAIL, NotificationChannel.CONSOLE);
            assertThat(store.attempts()).allMatch(attempt -> attempt.getStatus() == AttemptStatus.FAILED);
            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
        }

        @Test
        void noEligibleChannelFinishesWithoutAttempts() {
            Notification notification = otp();

            ClaimedSchedule claim = run(notification);

            assertThat(store.attempts()).isEmpty();
            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
            assertCodeRedacted(notification);
        }
    }

    // ── Vigencia del código ─────────────────────────────────────────────

    @Nested
    class Expiry {

        @Test
        void expiredBeforeDispatchNeverCreatesAnAttempt() {
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = otp();
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
            clock.set(NOW.plus(Duration.ofMinutes(5)));

            dispatcher(store, clock, false, email).dispatch(claim);

            assertThat(email.calls()).isZero();
            assertThat(store.attempts()).isEmpty();
            assertNotification(notification, NotificationStatus.EXPIRED);
            assertSchedule(claim, ScheduleStatus.EXPIRED);
            assertCodeRedacted(notification);
        }

        @Test
        void expiryAfterRenderButBeforeHandoffStopsDelivery() {
            Instant expiresAt = NOW.plusSeconds(10);
            TemplateRenderer slowRenderer = new TemplateRenderer(new InMemoryNotificationTemplateProvider()) {
                @Override
                public ChannelMessage render(Notification notification, NotificationChannel channel, Map<String, String> variables) {
                    ChannelMessage message = super.render(notification, channel, variables);
                    clock.set(expiresAt);
                    return message;
                }
            };
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = otp("sintetico@example.test", expiresAt);
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);

            dispatcher(store, clock, false, slowRenderer, email).dispatch(claim);

            assertThat(email.calls()).isZero();
            NotificationAttempt attempt = single();
            assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
            assertThat(attempt.getErrorCode()).isEqualTo(NotificationDispatcherService.CONTENT_EXPIRED);
            assertNotification(notification, NotificationStatus.EXPIRED);
            assertSchedule(claim, ScheduleStatus.EXPIRED);
            assertCodeRedacted(notification);
        }

        @Test
        void retryThatWouldArriveAfterExpiryIsNotScheduled() {
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.RETRYABLE));
            Notification notification = otp("sintetico@example.test", NOW.plusSeconds(30));

            ClaimedSchedule claim = run(notification, email);

            assertSchedule(claim, ScheduleStatus.FAILED);
            assertNotification(notification, NotificationStatus.FAILED);
            assertCodeRedacted(notification);
        }

        @Test
        void retryRevalidatesExpiry() {
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.RETRYABLE));
            Notification notification = otp("sintetico@example.test", NOW.plusSeconds(90));
            ClaimedSchedule claim = run(notification, email);
            assertSchedule(claim, ScheduleStatus.PENDING);

            // El reintento se reclama tarde, con el código ya vencido.
            clock.set(NOW.plusSeconds(120));
            ClaimedSchedule retry = store.reclaim(claim.scheduleId(), clock.instant());
            dispatcher(store, clock, false, email).dispatch(retry);

            assertThat(email.calls()).isEqualTo(1);
            assertNotification(notification, NotificationStatus.EXPIRED);
            assertCodeRedacted(notification);
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", "mañana"})
        void codeWithoutReadableExpiryIsNeverSent(String rawExpiry) {
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = notification(NotificationType.OTP_CODE, AggregateType.VERIFICATION,
                    "sintetico@example.test", Map.of(NotificationVariables.CODE, SYNTHETIC_CODE,
                            "expirationMinutes", "5", NotificationVariables.EXPIRES_AT, rawExpiry));

            run(notification, email);

            assertThat(email.calls()).isZero();
            assertNotification(notification, NotificationStatus.EXPIRED);
        }
    }

    // ── Propiedad del trabajo e interrupciones ──────────────────────────

    @Nested
    class OwnershipAndInterruption {

        @Test
        void staleClaimNeverInvokesTheProvider() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
            // Otro trabajador ya registró un intento después de este reclamo.
            NotificationAttempt newer = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                    NotificationChannel.EMAIL, "stub-email", 1, NOW);
            newer.markProcessing(NOW);
            store.putAttempt(newer);

            dispatcher(store, clock, false, email).dispatch(claim);

            assertThat(email.calls()).isZero();
            assertThat(store.appliedWrites).isEmpty();
            assertThat(store.attempts()).singleElement()
                    .extracting(NotificationAttempt::getStatus).isEqualTo(AttemptStatus.PROCESSING);
        }

        @Test
        void fenceFailureAtAttemptInsertNeverInvokesTheProvider() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
            store.beforeWrite = write -> {
                if (write.newAttempt() != null && store.attempts().isEmpty()) {
                    NotificationAttempt other = NotificationAttempt.create(notification.getId(), claim.scheduleId(),
                            NotificationChannel.EMAIL, "stub-email", 1, NOW);
                    other.markProcessing(NOW);
                    store.putAttempt(other);
                }
            };

            dispatcher(store, clock, false, email).dispatch(claim);

            assertThat(email.calls()).isZero();
            assertThat(store.appliedWrites).isEmpty();
            assertThat(logs()).anyMatch(line -> line.contains("NOTIFICATION_OWNERSHIP_LOST"));
        }

        @Test
        void acceptanceAfterRecoveryClosedTheWorkIsLoggedAndChangesNothing() {
            Notification notification = appointment();
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            // Mientras el proveedor responde, la recuperación cierra el trabajo.
            email.onSend = () -> store.recover(claim.scheduleId(), NOW.plusSeconds(1), NOW.plusSeconds(1));

            dispatcher(store, clock, false, email).dispatch(claim);

            assertThat(email.calls()).isEqualTo(1);
            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
            assertThat(store.attempts()).singleElement()
                    .extracting(NotificationAttempt::getStatus).isEqualTo(AttemptStatus.UNKNOWN);
            assertThat(logs()).anyMatch(line -> line.contains("NOTIFICATION_LATE_ACCEPTANCE"));
        }

        @Test
        void failedAttemptInsertPreventsProviderCallAndClosesWork() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
            store.beforeWrite = write -> {
                if (write.newAttempt() != null) {
                    throw new IllegalStateException("base sintética caída");
                }
            };

            assertThatCode(() -> dispatcher(store, clock, false, email).dispatch(claim)).doesNotThrowAnyException();

            assertThat(email.calls()).isZero();
            assertThat(store.attempts()).isEmpty();
            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
        }

        @Test
        void failureAfterProviderCallLeavesAttemptInFlightAndNeverResends() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
            store.beforeWrite = write -> {
                if (write.resolvedAttempt() != null) {
                    throw new IllegalStateException("base sintética caída");
                }
            };

            assertThatCode(() -> dispatcher(store, clock, false, email).dispatch(claim)).doesNotThrowAnyException();

            assertThat(email.calls()).isEqualTo(1);
            // Aceptación posible: queda en curso para la recuperación (W1b), sin otro envío.
            assertThat(single().getStatus()).isEqualTo(AttemptStatus.PROCESSING);
            assertSchedule(claim, ScheduleStatus.PROCESSING);
        }

        @Test
        void lostOwnershipAfterProviderCallRecordsOnlyTheOwnAttempt() {
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = appointment();
            ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
            NotificationDispatcherService dispatcher = dispatcher(store, clock, false, email);
            store.beforeWrite = write -> {
                if (write.resolvedAttempt() != null) {
                    // Cancelación concurrente: el schedule deja de estar reclamado.
                    forceSchedule(claim, ScheduleStatus.CANCELLED);
                }
            };

            dispatcher.dispatch(claim);

            assertThat(single().getStatus()).isEqualTo(AttemptStatus.ACCEPTED);
            assertNotification(notification, NotificationStatus.PROCESSING);
            assertSchedule(claim, ScheduleStatus.CANCELLED);
        }

        @Test
        void resumedWorkWithInFlightAttemptBecomesUnknownWithoutResending() {
            StubProvider email = StubProvider.qualifiedEmail(() -> outcome(DeliveryOutcome.ACCEPTED));
            Notification notification = otp();
            store.seedAndClaim(notification, NOW);
            NotificationAttempt inFlight = NotificationAttempt.create(notification.getId(),
                    scheduleIdOf(notification), NotificationChannel.EMAIL, "stub-email", 1, NOW);
            inFlight.markProcessing(NOW);
            store.putAttempt(inFlight);
            ClaimedSchedule claim = new ClaimedSchedule(scheduleIdOf(notification), 1);

            dispatcher(store, clock, false, email).dispatch(claim);

            assertThat(email.calls()).isZero();
            assertThat(single().getStatus()).isEqualTo(AttemptStatus.UNKNOWN);
            assertThat(single().getErrorCode()).isEqualTo(NotificationDispatcherService.ACCEPTANCE_UNKNOWN);
            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
            assertCodeRedacted(notification);
        }

        @Test
        void beginFailureDoesNotPropagate() {
            NotificationDispatchStore broken = new NotificationDispatchStore() {
                @Override
                public Begin begin(UUID scheduleId, int claimedAttemptCount, Instant now) {
                    throw new IllegalStateException("base sintética caída");
                }

                @Override
                public WriteResult write(DispatchWrite write) {
                    throw new AssertionError("no debe escribir");
                }

                @Override
                public List<UUID> findInterrupted(Instant staleBefore, int limit) {
                    throw new AssertionError("el despacho no recupera");
                }

                @Override
                public Recovery recover(UUID scheduleId, Instant staleBefore, Instant now) {
                    throw new AssertionError("el despacho no recupera");
                }
            };
            StubProvider email = StubProvider.email(() -> outcome(DeliveryOutcome.ACCEPTED));
            NotificationDispatcherService dispatcher = new NotificationDispatcherService(broken,
                    new ProviderRegistry(List.of(email)), new TemplateRenderer(new InMemoryNotificationTemplateProvider()),
                    new co.edu.unicauca.piedrazul.backend.notifications.domain.policy.RetryPolicy(),
                    new co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy(),
                    new RecipientEmailPolicy(VALIDATOR), appointmentId -> true, false, clock);

            assertThatCode(() -> dispatcher.dispatch(new ClaimedSchedule(UUID.randomUUID(), 0)))
                    .doesNotThrowAnyException();
            assertThat(email.calls()).isZero();
        }
    }

    // ── HR-1: deducción conservadora de resultados de proveedores previos ─

    @Nested
    class LegacyInference {

        @ParameterizedTest(name = "{0}/{1}")
        @org.junit.jupiter.params.provider.CsvSource(nullValues = "null", value = {
                "FAILED, UNKNOWN",
                "FAILED, null",
                "BOUNCED, PERMANENT",
                "UNDELIVERED, null",
                "CANCELLED, TEMPORARY"
        })
        void uncertainLegacyResultNeverFallsBackOrRetries(AttemptStatus status, FailureType failureType) {
            // Un resultado sin la forma explícita de no aceptación, con la consola elegible después.
            ConsoleCounter consoleCounter = new ConsoleCounter();
            StubProvider email = StubProvider.email(() -> new NotificationSendResult(
                    "stub-email", NotificationChannel.EMAIL, null, status, failureType, "LEGACY_FAILURE", "sintético"));
            Notification notification = appointment();

            ClaimedSchedule claim = run(notification, email, consoleCounter);

            assertThat(email.calls()).isEqualTo(1);
            assertThat(consoleCounter.calls).isZero();
            assertAcceptanceUnknown(notification, claim);
        }

        @Test
        void explicitLegacyPermanentFailureStillFallsBack() {
            ConsoleCounter consoleCounter = new ConsoleCounter();
            StubProvider email = StubProvider.email(() -> result(AttemptStatus.FAILED, FailureType.PERMANENT));

            run(appointment(), email, consoleCounter);

            assertThat(consoleCounter.calls).isEqualTo(1);
        }
    }

    // ── HR-2: diagnósticos del proveedor sin contenido sensible ─────────

    @Nested
    class ProviderDiagnostics {

        private static final String ECHO = "tu código es " + SYNTHETIC_CODE;

        @Test
        void rejectionEchoingTheCodeIsPersistedAndLoggedWithoutIt() {
            StubProvider email = StubProvider.qualifiedEmail(() -> new NotificationSendResult("stub-email",
                    NotificationChannel.EMAIL, null, AttemptStatus.FAILED, FailureType.PERMANENT,
                    "BAD_" + SYNTHETIC_CODE, ECHO));
            Notification notification = otp();

            ClaimedSchedule claim = run(notification, email);

            NotificationAttempt attempt = single();
            assertThat(attempt.getFailureType()).isEqualTo(FailureType.PERMANENT);
            assertThat(attempt.getErrorCode()).isEqualTo(NotificationDispatcherService.DIAGNOSTIC_REDACTED);
            assertThat(attempt.getErrorMessage()).startsWith("Rechazado por el proveedor");
            assertNoSecretInDiagnostics();
            assertNotification(notification, NotificationStatus.FAILED);
            assertSchedule(claim, ScheduleStatus.FAILED);
        }

        @Test
        void retryableEchoKeepsRetryButNotTheText() {
            StubProvider email = StubProvider.qualifiedEmail(() -> new NotificationSendResult("stub-email",
                    NotificationChannel.EMAIL, null, AttemptStatus.FAILED, FailureType.TEMPORARY,
                    "THROTTLED", ECHO));
            Notification notification = otp();

            ClaimedSchedule claim = run(notification, email);

            assertSchedule(claim, ScheduleStatus.PENDING);
            NotificationAttempt attempt = single();
            assertThat(attempt.getFailureType()).isEqualTo(FailureType.TEMPORARY);
            // Código seguro conservado; el texto del proveedor no.
            assertThat(attempt.getErrorCode()).isEqualTo("THROTTLED");
            assertThat(attempt.getErrorMessage()).doesNotContain("tu código");
            assertNoSecretInDiagnostics();
        }

        @Test
        void exceptionWithEvidenceEchoingTheCodeIsRetriedWithoutTheText() {
            StubProvider email = StubProvider.qualifiedEmail(() -> {
                throw new NotificationDispatchException("THROTTLED", ECHO, DeliveryOutcome.RETRYABLE, null);
            });

            ClaimedSchedule claim = run(otp(), email);

            assertSchedule(claim, ScheduleStatus.PENDING);
            assertNoSecretInDiagnostics();
        }

        @Test
        void exceptionWithoutEvidenceEchoingTheCodeStaysUnknownWithoutIt() {
            StubProvider email = StubProvider.qualifiedEmail(() -> {
                throw new NotificationDispatchException("ECHO " + SYNTHETIC_CODE, ECHO);
            });
            Notification notification = otp();

            ClaimedSchedule claim = run(notification, email);

            assertAcceptanceUnknown(notification, claim);
            assertThat(single().getErrorMessage()).contains(NotificationDispatcherService.DIAGNOSTIC_REDACTED);
            assertNoSecretInDiagnostics();
        }

        @Test
        void acceptedMessageIdContainingTheCodeIsDropped() {
            StubProvider email = StubProvider.qualifiedEmail(() -> new NotificationSendResult("stub-email",
                    NotificationChannel.EMAIL, "msg-" + SYNTHETIC_CODE, AttemptStatus.ACCEPTED, null, null, null));

            run(otp(), email);

            assertThat(single().getStatus()).isEqualTo(AttemptStatus.ACCEPTED);
            assertThat(single().getProviderMessageId()).isNull();
            assertNoSecretInDiagnostics();
        }

        @Test
        void nonSensitiveDiagnosticsArePreserved() {
            StubProvider email = StubProvider.email(() -> new NotificationSendResult("stub-email",
                    NotificationChannel.EMAIL, null, AttemptStatus.FAILED, FailureType.PERMANENT,
                    "SYNTHETIC_BOUNCE", "buzón sintético lleno"));

            run(appointment(), email);

            NotificationAttempt attempt = store.attempts().get(0);
            assertThat(attempt.getErrorCode()).isEqualTo("SYNTHETIC_BOUNCE");
            assertThat(attempt.getErrorMessage()).isEqualTo("buzón sintético lleno");
        }

        @Test
        void malformedProviderCodeIsReplaced() {
            StubProvider email = StubProvider.email(() -> new NotificationSendResult("stub-email",
                    NotificationChannel.EMAIL, null, AttemptStatus.FAILED, FailureType.PERMANENT,
                    "code with spaces\nand a new line", "texto"));

            run(appointment(), email);

            assertThat(store.attempts().get(0).getErrorCode()).isEqualTo(NotificationDispatcherService.DIAGNOSTIC_REDACTED);
        }

        private void assertNoSecretInDiagnostics() {
            assertThat(store.attempts()).allSatisfy(attempt -> {
                assertThat(String.valueOf(attempt.getErrorCode())).doesNotContain(SYNTHETIC_CODE);
                assertThat(String.valueOf(attempt.getErrorMessage())).doesNotContain(SYNTHETIC_CODE);
                assertThat(String.valueOf(attempt.getProviderMessageId())).doesNotContain(SYNTHETIC_CODE);
            });
            assertThat(logs()).isNotEmpty().noneMatch(line -> line.contains(SYNTHETIC_CODE));
        }
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private ClaimedSchedule run(Notification notification, NotificationProvider... providers) {
        ClaimedSchedule claim = store.seedAndClaim(notification, NOW);
        dispatcher(store, clock, false, providers).dispatch(claim);
        return claim;
    }

    private ClaimedSchedule runUntilSettled(NotificationDispatcherService dispatcher, ClaimedSchedule claim) {
        for (int i = 0; i < 10; i++) {
            dispatcher.dispatch(claim);
            NotificationSchedule schedule = store.schedule(claim.scheduleId());

            if (schedule.getStatus() != ScheduleStatus.PENDING) {
                return claim;
            }

            clock.set(schedule.getNextRetryAt());
            claim = store.reclaim(claim.scheduleId(), clock.instant());
        }

        throw new AssertionError("El despacho no terminó");
    }

    private UUID scheduleIdOf(Notification notification) {
        return store.lastScheduleFor(notification.getId());
    }

    private void forceSchedule(ClaimedSchedule claim, ScheduleStatus status) {
        store.forceScheduleStatus(claim.scheduleId(), status, NOW);
    }

    private void assertAcceptanceUnknown(Notification notification, ClaimedSchedule claim) {
        NotificationAttempt attempt = single();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.UNKNOWN);
        assertThat(attempt.getFailureType()).isEqualTo(FailureType.UNKNOWN);
        assertThat(attempt.getErrorCode()).isEqualTo(NotificationDispatcherService.ACCEPTANCE_UNKNOWN);
        assertThat(attempt.getFailedAt()).isNull();
        assertNotification(notification, NotificationStatus.FAILED);
        assertSchedule(claim, ScheduleStatus.FAILED);
        assertThat(store.schedule(claim.scheduleId()).getNextRetryAt()).isNull();
        assertThat(logs()).anyMatch(line -> line.startsWith("NOTIFICATION_ACCEPTANCE_UNKNOWN"));
    }

    private void assertNotification(Notification notification, NotificationStatus status) {
        assertThat(store.notification(notification.getId()).getStatus()).isEqualTo(status);
    }

    private void assertSchedule(ClaimedSchedule claim, ScheduleStatus status) {
        assertThat(store.schedule(claim.scheduleId()).getStatus()).isEqualTo(status);
    }

    private void assertCodeRedacted(Notification notification) {
        Map<String, String> variables = store.notification(notification.getId()).getVariables();
        assertThat(variables).doesNotContainKey(NotificationVariables.CODE);
        assertThat(variables).containsKeys("expirationMinutes", NotificationVariables.EXPIRES_AT);
    }

    /** Ninguna escritura llegó a registrar el intento como en curso. */
    private void assertNeverProcessing() {
        assertThat(store.appliedWrites)
                .allMatch(write -> write.newAttempt() == null || write.newAttempt().getStatus() != AttemptStatus.PROCESSING);
    }

    private NotificationAttempt single() {
        assertThat(store.attempts()).hasSize(1);
        return store.attempts().get(0);
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Consola que solo cuenta llamadas (sigue siendo diagnóstico, no entrega). */
    private final class ConsoleCounter implements NotificationProvider {
        int calls;

        @Override
        public NotificationChannel channel() {
            return NotificationChannel.CONSOLE;
        }

        @Override
        public String providerName() {
            return "console";
        }

        @Override
        public NotificationSendResult send(ChannelMessage message, RecipientSnapshot recipient) {
            calls++;
            return console.send(message, recipient);
        }
    }

    /** Proveedor que mide su salud con un circuit breaker propio. */
    private static final class BreakerProvider implements NotificationProvider {
        private final CircuitBreaker breaker;
        int calls;

        private BreakerProvider(CircuitBreaker breaker) {
            this.breaker = breaker;
        }

        @Override
        public boolean qualifiesForOtpDelivery() {
            return true;
        }

        @Override
        public NotificationChannel channel() {
            return NotificationChannel.EMAIL;
        }

        @Override
        public String providerName() {
            return "breaker-email";
        }

        @Override
        public NotificationSendResult send(ChannelMessage message, RecipientSnapshot recipient) {
            return breaker.executeSupplier(() -> {
                calls++;
                return outcome(DeliveryOutcome.ACCEPTED);
            });
        }
    }
}
