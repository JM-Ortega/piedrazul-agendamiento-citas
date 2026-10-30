package co.edu.unicauca.piedrazul.backend.verification.infrastructure.delivery;

import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.api.OtpDeliveryMode;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationVariables;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationVerificationCodeSenderTest {

    @Mock
    private NotificationModuleApi notificationModuleApi;

    @InjectMocks
    private NotificationVerificationCodeSender sender;

    @Captor
    private ArgumentCaptor<SendNotificationCommand> commandCaptor;

    @Test
    void sendCodeShouldUseExplicitRecipientIdRatherThanDeriveItFromSubject() {
        String subject = "1061234567";
        UUID recipientId = UUID.randomUUID();
        UUID verificationCodeId = UUID.randomUUID();

        sender.sendCode(subject, "Juana Perez", "3001234567", "juana@example.com", "123456", 5,
                Instant.parse("2026-01-01T12:05:00Z"), recipientId, verificationCodeId);

        verify(notificationModuleApi).sendNow(commandCaptor.capture());

        assertThat(commandCaptor.getValue().recipient().recipientId()).isEqualTo(recipientId);
        assertThat(commandCaptor.getValue().aggregate().id()).isEqualTo(verificationCodeId);
    }

    @Test
    void sendCodePassesTheRealExpiryToNotifications() {
        Instant expiresAt = Instant.parse("2026-01-01T12:05:00Z");

        sender.sendCode("1061234567", "Juana Perez", null, "juana@example.com", "123456", 5,
                expiresAt, UUID.randomUUID(), UUID.randomUUID());

        verify(notificationModuleApi).sendNow(commandCaptor.capture());

        assertThat(commandCaptor.getValue().variables())
                .containsEntry(NotificationVariables.EXPIRES_AT, expiresAt.toString())
                .containsEntry(NotificationVariables.CODE, "123456");
    }

    @ParameterizedTest
    @EnumSource(OtpDeliveryMode.class)
    void deliveryIsAvailableOnlyWhenNotificationsReportsAChannel(OtpDeliveryMode mode) {
        when(notificationModuleApi.otpDeliveryMode()).thenReturn(mode);

        assertThat(sender.isDeliveryAvailable()).isEqualTo(mode != OtpDeliveryMode.UNAVAILABLE);
    }

    @Test
    void deliveryIsUnavailableWhenNotificationsReportsNoMode() {
        when(notificationModuleApi.otpDeliveryMode()).thenReturn(null);

        assertThat(sender.isDeliveryAvailable()).isFalse();
    }

    @Test
    void recipientUsabilityIsDecidedByNotificationsWithTheExactValues() {
        when(notificationModuleApi.isUsableOtpRecipient("3001234567", "ana@example.invalid")).thenReturn(false);
        when(notificationModuleApi.isUsableOtpRecipient(null, "ana@example.com")).thenReturn(true);

        assertThat(sender.isRecipientUsable("3001234567", "ana@example.invalid")).isFalse();
        assertThat(sender.isRecipientUsable(null, "ana@example.com")).isTrue();
    }
}
