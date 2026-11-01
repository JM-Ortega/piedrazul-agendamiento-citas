package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRedactionTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void redactionRemovesOnlyTheCode() {
        Notification otp = notification(NotificationType.OTP_CODE, Map.of(
                NotificationVariables.CODE, "000111", "expirationMinutes", "5",
                NotificationVariables.EXPIRES_AT, NOW.plusSeconds(300).toString()));

        assertThat(otp.redactSensitiveVariables()).isTrue();
        assertThat(otp.getVariables())
                .doesNotContainKey(NotificationVariables.CODE)
                .containsKeys("expirationMinutes", NotificationVariables.EXPIRES_AT);
        assertThat(otp.redactSensitiveVariables()).isFalse();
    }

    @Test
    void nonSensitiveTypesAreUntouched() {
        Notification appointment = notification(NotificationType.APPOINTMENT_REMINDER_2_DAYS, Map.of("code", "no-secreto"));

        assertThat(appointment.redactSensitiveVariables()).isFalse();
        assertThat(appointment.getVariables()).containsKey("code");
    }

    @Test
    void expiryIsExclusiveAndFailsClosed() {
        Instant expiresAt = NOW.plusSeconds(300);
        Notification otp = notification(NotificationType.OTP_CODE, Map.of(NotificationVariables.EXPIRES_AT, expiresAt.toString()));

        assertThat(otp.isExpiredAt(expiresAt.minusNanos(1))).isFalse();
        assertThat(otp.isExpiredAt(expiresAt)).isTrue();
        assertThat(notification(NotificationType.OTP_CODE, Map.of()).isExpiredAt(NOW)).isTrue();
        assertThat(notification(NotificationType.APPOINTMENT_REMINDER_2_DAYS, Map.of()).isExpiredAt(NOW)).isFalse();
    }

    private static Notification notification(NotificationType type, Map<String, String> variables) {
        return Notification.create(type, new AggregateReference(AggregateType.VERIFICATION, UUID.randomUUID()),
                new RecipientSnapshot(UUID.randomUUID(), "Sintético", null, "sintetico@example.test",
                        Locale.forLanguageTag("es-CO"), null),
                new ChannelPreference(java.util.List.of(NotificationChannel.EMAIL)), variables, NOW);
    }
}
