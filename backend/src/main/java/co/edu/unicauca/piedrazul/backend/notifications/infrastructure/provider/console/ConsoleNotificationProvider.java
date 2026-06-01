package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console;

import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationDispatchException;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ConsoleLogMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class ConsoleNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(ConsoleNotificationProvider.class);
    private static final String PROVIDER_NAME = "console";

    private final boolean showSensitiveContent;

    public ConsoleNotificationProvider(boolean showSensitiveContent) {
        this.showSensitiveContent = showSensitiveContent;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.CONSOLE;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public NotificationSendResult send(
            ChannelMessage message,
            RecipientSnapshot recipient
    ) {
        if (!(message instanceof ConsoleLogMessage consoleMessage)) {
            throw new NotificationDispatchException(
                    "INVALID_CONSOLE_MESSAGE",
                    "ConsoleNotificationProvider solo acepta ConsoleLogMessage"
            );
        }

        String renderedMessage = consoleMessage.sensitive() && !showSensitiveContent
                ? "[contenido sensible oculto]"
                : consoleMessage.message();

        log.info(
                "[CONSOLE_NOTIFICATION] recipient={} message={}",
                maskRecipient(recipient),
                renderedMessage
        );

        return new NotificationSendResult(
                PROVIDER_NAME,
                NotificationChannel.CONSOLE,
                "console-" + UUID.randomUUID(),
                AttemptStatus.DELIVERED,
                null,
                null,
                null
        );
    }

    private String maskRecipient(RecipientSnapshot recipient) {
        if (recipient == null) {
            return "unknown";
        }

        if (recipient.phoneE164() != null && !recipient.phoneE164().isBlank()) {
            return maskPhone(recipient.phoneE164());
        }

        if (recipient.email() != null && !recipient.email().isBlank()) {
            return maskEmail(recipient.email());
        }

        if (recipient.displayName() != null && !recipient.displayName().isBlank()) {
            return recipient.displayName();
        }

        return "unknown";
    }

    private String maskPhone(String phone) {
        if (phone.length() <= 4) {
            return "****";
        }

        return "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf("@");

        if (atIndex <= 1) {
            return "***";
        }

        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}