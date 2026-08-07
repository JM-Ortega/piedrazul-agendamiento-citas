package co.edu.unicauca.piedrazul.backend.verification.infrastructure.delivery;

import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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

        sender.sendCode(subject, "Juana Perez", "3001234567", "juana@example.com", "123456", 5, recipientId, verificationCodeId);

        verify(notificationModuleApi).sendNow(commandCaptor.capture());

        assertThat(commandCaptor.getValue().recipient().recipientId()).isEqualTo(recipientId);
        assertThat(commandCaptor.getValue().aggregate().id()).isEqualTo(verificationCodeId);
    }
}
