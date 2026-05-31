package co.edu.unicauca.piedrazul.backend.verification.infrastructure.delivery;

import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
    public void sendCode(String subject, String displayName, String phone, String email, String code, int expirationMinutes) {
        UUID subjectId = parseOrDerive(subject);

        RecipientSnapshot recipient = new RecipientSnapshot(
                subjectId,
                RecipientType.PATIENT,
                displayName,
                phone,
                email,
                DEFAULT_LOCALE,
                null  // sin preferencia explícita → el fallback policy decide: WhatsApp → SMS → Email → Console
        );

        Map<String, String> variables = Map.of(
                "code", code,
                "expirationMinutes", String.valueOf(expirationMinutes),
                "destination", phone != null ? phone : email
        );

        notificationModuleApi.sendNow(new SendNotificationCommand(
                NotificationType.OTP_CODE,
                new AggregateReference(AggregateType.VERIFICATION, subjectId),
                recipient,
                variables
        ));
    }

    private UUID parseOrDerive(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            return UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8));
        }
    }
}
