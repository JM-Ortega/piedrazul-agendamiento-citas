package co.edu.unicauca.piedrazul.backend.notifications.domain.message;

import java.util.Map;

public record WhatsAppTemplateMessage(
        String templateName,
        String language,
        Map<String, String> variables
) implements ChannelMessage {
}