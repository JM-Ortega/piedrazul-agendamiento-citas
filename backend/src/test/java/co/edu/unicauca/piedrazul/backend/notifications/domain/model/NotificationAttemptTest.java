package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationAttemptTest {

    // ─────────────────────────────────────────────
    // Truncado de error_message (max 500, columna V001)
    // ─────────────────────────────────────────────

    @Test
    void markFailedShouldTruncateErrorMessageWhenLongerThan500Characters() {
        NotificationAttempt attempt = NotificationAttempt.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sendgrid",
                1,
                Instant.now()
        );
        attempt.markProcessing(Instant.now());

        String longMessage = "x".repeat(600);

        attempt.markFailed(FailureType.PERMANENT, "ERR", longMessage, Instant.now());

        assertThat(attempt.getErrorMessage()).hasSize(500);
        assertThat(attempt.getErrorMessage()).isEqualTo("x".repeat(500));
    }

    @Test
    void markFailedShouldKeepErrorMessageUnchangedWhenAtOrBelow500Characters() {
        NotificationAttempt attempt = NotificationAttempt.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sendgrid",
                1,
                Instant.now()
        );
        attempt.markProcessing(Instant.now());

        String message = "x".repeat(500);

        attempt.markFailed(FailureType.PERMANENT, "ERR", message, Instant.now());

        assertThat(attempt.getErrorMessage()).isEqualTo(message);
    }

    @Test
    void markFailedShouldAllowNullErrorMessage() {
        NotificationAttempt attempt = NotificationAttempt.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sendgrid",
                1,
                Instant.now()
        );
        attempt.markProcessing(Instant.now());

        attempt.markFailed(FailureType.UNKNOWN, null, null, Instant.now());

        assertThat(attempt.getErrorMessage()).isNull();
    }

    @Test
    void markBouncedShouldTruncateErrorMessageWhenLongerThan500Characters() {
        NotificationAttempt attempt = NotificationAttempt.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sendgrid",
                1,
                Instant.now()
        );
        attempt.markProcessing(Instant.now());
        attempt.markSent("provider-message-id", Instant.now());

        String longMessage = "y".repeat(600);

        attempt.markBounced("ERR", longMessage, Instant.now());

        assertThat(attempt.getErrorMessage()).hasSize(500);
    }

    @Test
    void markUndeliveredShouldTruncateErrorMessageWhenLongerThan500Characters() {
        NotificationAttempt attempt = NotificationAttempt.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "sendgrid",
                1,
                Instant.now()
        );
        attempt.markProcessing(Instant.now());
        attempt.markSent("provider-message-id", Instant.now());

        String longMessage = "z".repeat(600);

        attempt.markUndelivered("ERR", longMessage, Instant.now());

        assertThat(attempt.getErrorMessage()).hasSize(500);
    }
}
