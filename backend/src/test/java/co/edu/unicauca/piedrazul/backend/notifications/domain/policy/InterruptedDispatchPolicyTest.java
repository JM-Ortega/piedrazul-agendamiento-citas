package co.edu.unicauca.piedrazul.backend.notifications.domain.policy;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.InterruptedDispatchPolicy.Decision;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.InterruptedDispatchPolicy.Resolution;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Matriz de decisión de la recuperación. Datos exclusivamente sintéticos. */
class InterruptedDispatchPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Instant STALE_BEFORE = NOW.minus(Duration.ofMinutes(15));
    private static final Instant OLD = STALE_BEFORE.minusSeconds(60);
    private static final Instant FRESH = STALE_BEFORE.plusSeconds(60);

    private final NotificationSchedule schedule = claimedAt(OLD);
    private final Notification notification = otp(NotificationStatus.PROCESSING, NOW.plusSeconds(300));

    @Test
    void oldClaimWithoutAttemptsIsRequeuedWithoutConsumingAttempts() {
        Decision decision = resolve(List.of());

        assertThat(decision.resolution()).isEqualTo(Resolution.REQUEUED);
        assertThat(decision.closedAttempts()).isEmpty();
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.PENDING);
        assertThat(schedule.getNextRetryAt()).isEqualTo(NOW);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notification.dispatchRecoveries()).isEqualTo(1);
        assertThat(notification.getVariables()).containsKey(NotificationVariables.CODE);
    }

    @Test
    void claimThatNeverBeganIsRequeuedToo() {
        Notification pending = otp(NotificationStatus.PENDING, NOW.plusSeconds(300));

        Decision decision = InterruptedDispatchPolicy.resolve(schedule, pending, List.of(), STALE_BEFORE, NOW);

        assertThat(decision.resolution()).isEqualTo(Resolution.REQUEUED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.PENDING);
    }

    @Test
    void provenNonAcceptanceAllowsRequeue() {
        Decision decision = resolve(List.of(failed(1, FailureType.TEMPORARY)));

        assertThat(decision.resolution()).isEqualTo(Resolution.REQUEUED);
    }

    @Test
    void recentClaimIsNotTouched() {
        NotificationSchedule recent = claimedAt(FRESH);

        Decision decision = InterruptedDispatchPolicy.resolve(recent, notification, List.of(), STALE_BEFORE, NOW);

        assertThat(decision.resolution()).isEqualTo(Resolution.NONE);
        assertThat(recent.getStatus()).isEqualTo(ScheduleStatus.PROCESSING);
    }

    @Test
    void recentInFlightAttemptProtectsAnOldClaim() {
        NotificationAttempt attempt = inFlight(1, FRESH);

        assertThat(resolve(List.of(attempt)).resolution()).isEqualTo(Resolution.NONE);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.PROCESSING);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.PROCESSING);
    }

    @Test
    void oldInFlightAttemptBecomesUnknownAndEndsTheWork() {
        NotificationAttempt attempt = inFlight(1, OLD);

        Decision decision = resolve(List.of(attempt));

        assertThat(decision.resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
        assertThat(decision.closedAttempts()).containsExactly(attempt);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.UNKNOWN);
        assertThat(attempt.getFailureType()).isEqualTo(FailureType.UNKNOWN);
        assertThat(attempt.getFailedAt()).isNull();
        assertThat(attempt.getErrorCode()).isEqualTo(InterruptedDispatchPolicy.ACCEPTANCE_UNKNOWN);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.FAILED);
        assertThat(notification.getVariables()).doesNotContainKey(NotificationVariables.CODE);
    }

    @Test
    void anAttemptWithoutFinalResultIsNeverInferredAsNotInvoked() {
        // El reclamo es posterior al intento: aun así el intento pudo llegar al proveedor.
        NotificationSchedule reclaimed = claimedAt(OLD.plusSeconds(30));
        NotificationAttempt attempt = inFlight(1, OLD);

        Decision decision = InterruptedDispatchPolicy.resolve(reclaimed, notification, List.of(attempt), STALE_BEFORE, NOW);

        assertThat(decision.resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
    }

    @Test
    void anUnknownInTheHistoryBlocksRequeue() {
        Decision decision = resolve(List.of(failed(1, FailureType.TEMPORARY), unknown(2)));

        assertThat(decision.resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
        assertThat(decision.closedAttempts()).isEmpty();
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void acceptedAttemptIsPreservedAndWinsOverAnInFlightOne() {
        NotificationAttempt accepted = accepted(1);
        NotificationAttempt attempt = inFlight(2, OLD);

        Decision decision = resolve(List.of(accepted, attempt));

        assertThat(decision.resolution()).isEqualTo(Resolution.ACCEPTED);
        assertThat(accepted.getStatus()).isEqualTo(AttemptStatus.ACCEPTED);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.UNKNOWN);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.ACCEPTED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SENT);
        assertThat(notification.getVariables()).doesNotContainKey(NotificationVariables.CODE);
    }

    @Test
    void expiredContentIsNotRequeued() {
        Notification expired = otp(NotificationStatus.PROCESSING, NOW);

        Decision decision = InterruptedDispatchPolicy.resolve(schedule, expired, List.of(), STALE_BEFORE, NOW);

        assertThat(decision.resolution()).isEqualTo(Resolution.EXPIRED);
        assertThat(expired.getStatus()).isEqualTo(NotificationStatus.EXPIRED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.EXPIRED);
        assertThat(expired.getVariables()).doesNotContainKey(NotificationVariables.CODE);
    }

    @Test
    void possibleAcceptanceIsNotReportedAsExpired() {
        Notification expired = otp(NotificationStatus.PROCESSING, NOW);

        Decision decision = InterruptedDispatchPolicy.resolve(schedule, expired, List.of(inFlight(1, OLD)), STALE_BEFORE, NOW);

        assertThat(decision.resolution()).isEqualTo(Resolution.ACCEPTANCE_UNKNOWN);
        assertThat(expired.getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void requeuesAreBounded() {
        for (int requeue = 0; requeue < InterruptedDispatchPolicy.MAX_REQUEUES; requeue++) {
            notification.recordDispatchRecovery(NOW);
        }

        Decision decision = resolve(List.of());

        assertThat(decision.resolution()).isEqualTo(Resolution.REQUEUES_EXHAUSTED);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.FAILED);
        assertThat(notification.getVariables()).doesNotContainKey(NotificationVariables.CODE);
    }

    @Test
    void scheduleOfSettledNotificationFollowsIt() {
        Notification cancelled = otp(NotificationStatus.CANCELLED, NOW.plusSeconds(300));

        Decision decision = InterruptedDispatchPolicy.resolve(schedule, cancelled, List.of(), STALE_BEFORE, NOW);

        assertThat(decision.resolution()).isEqualTo(Resolution.SETTLED);
        assertThat(cancelled.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.CANCELLED);
        assertThat(cancelled.getVariables()).doesNotContainKey(NotificationVariables.CODE);
    }

    @Test
    void terminalScheduleOnlyClosesItsOrphanAttempt() {
        schedule.cancel(OLD);
        Notification cancelled = otp(NotificationStatus.CANCELLED, NOW.plusSeconds(300));
        NotificationAttempt orphan = inFlight(1, OLD);

        Decision decision = InterruptedDispatchPolicy.resolve(schedule, cancelled, List.of(orphan), STALE_BEFORE, NOW);

        assertThat(decision.resolution()).isEqualTo(Resolution.ATTEMPT_CLOSED);
        assertThat(orphan.getStatus()).isEqualTo(AttemptStatus.UNKNOWN);
        assertThat(cancelled.getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.CANCELLED);
        assertThat(cancelled.getVariables()).doesNotContainKey(NotificationVariables.CODE);
    }

    @Test
    void terminalScheduleWithoutInFlightAttemptsIsUntouched() {
        schedule.markSent(OLD);

        assertThat(resolve(List.of(accepted(1))).resolution()).isEqualTo(Resolution.NONE);
        assertThat(schedule.getStatus()).isEqualTo(ScheduleStatus.SENT);
    }

    private Decision resolve(List<NotificationAttempt> attempts) {
        return InterruptedDispatchPolicy.resolve(schedule, notification, attempts, STALE_BEFORE, NOW);
    }

    private NotificationSchedule claimedAt(Instant claimedAt) {
        NotificationSchedule claimed = NotificationSchedule.create(UUID.randomUUID(), claimedAt, claimedAt);
        claimed.markProcessing(claimedAt);
        return claimed;
    }

    private Notification otp(NotificationStatus status, Instant expiresAt) {
        return Notification.reconstruct(
                UUID.randomUUID(),
                NotificationType.OTP_CODE,
                new AggregateReference(AggregateType.VERIFICATION, UUID.randomUUID()),
                new RecipientSnapshot(UUID.randomUUID(), "Sintético", "+570000000000", "sintetico@example.test",
                        Locale.forLanguageTag("es-CO"), null),
                new ChannelPreference(List.of(NotificationChannel.EMAIL)),
                Map.of(NotificationVariables.CODE, "000111", NotificationVariables.EXPIRES_AT, expiresAt.toString()),
                "sintetico:" + UUID.randomUUID(),
                status,
                OLD,
                OLD
        );
    }

    private NotificationAttempt inFlight(int number, Instant at) {
        NotificationAttempt attempt = NotificationAttempt.create(notification.getId(), schedule.getId(),
                NotificationChannel.EMAIL, "stub", number, at);
        attempt.markProcessing(at);
        return attempt;
    }

    private NotificationAttempt failed(int number, FailureType failureType) {
        NotificationAttempt attempt = inFlight(number, OLD);
        attempt.markFailed(failureType, "SYNTHETIC", "fallo sintético", OLD);
        return attempt;
    }

    private NotificationAttempt unknown(int number) {
        NotificationAttempt attempt = inFlight(number, OLD);
        attempt.markAcceptanceUnknown("ACCEPTANCE_UNKNOWN", "sintético", OLD);
        return attempt;
    }

    private NotificationAttempt accepted(int number) {
        NotificationAttempt attempt = inFlight(number, OLD);
        attempt.markAccepted("stub-message", OLD);
        return attempt;
    }
}
