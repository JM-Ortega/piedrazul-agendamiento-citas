package co.edu.unicauca.piedrazul.backend.verification.infrastructure.delivery;

import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.api.OtpDeliveryMode;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class NotificationVerificationCodeSender implements VerificationCodeSender {

    private static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("es-CO");

    private final NotificationModuleApi notificationModuleApi;

    public NotificationVerificationCodeSender(NotificationModuleApi notificationModuleApi) {
        this.notificationModuleApi = notificationModuleApi;
    }

    @Override
    public boolean isDeliveryAvailable() {
        // Lista cerrada: un modo ausente o desconocido cuenta como no disponible.
        OtpDeliveryMode mode = notificationModuleApi.otpDeliveryMode();
        return mode == OtpDeliveryMode.EMAIL || mode == OtpDeliveryMode.DIAGNOSTIC_CONSOLE;
    }

    @Override
    public boolean isRecipientUsable(String phone, String email) {
        return notificationModuleApi.isUsableOtpRecipient(phone, email);
    }

    @Override
    public void sendCode(String subject, String displayName, String phone, String email, String code, int expirationMinutes, Instant expiresAt, UUID recipientId, UUID verificationCodeId) {
        RecipientSnapshot recipient = new RecipientSnapshot(
                recipientId,
                displayName,
                phone,
                email,
                DEFAULT_LOCALE,
                null  // sin preferencia explícita → el fallback policy decide: WhatsApp → SMS → Email → Console
        );

        Map<String, String> variables = Map.of(
                NotificationVariables.CODE, code,
                "expirationMinutes", String.valueOf(expirationMinutes),
                // El despacho no entrega el código al proveedor a partir de este instante.
                NotificationVariables.EXPIRES_AT, expiresAt.toString(),
                "destination", phone != null ? phone : email
        );

        notificationModuleApi.sendNow(new SendNotificationCommand(
                NotificationType.OTP_CODE,
                new AggregateReference(AggregateType.VERIFICATION, verificationCodeId),
                recipient,
                variables
        ));
    }
}
