package co.edu.unicauca.piedrazul.backend.notifications.domain.message;

public sealed interface ChannelMessage
        permits WhatsAppTemplateMessage,
                SmsTextMessage,
                EmailMessage,
                ConsoleLogMessage {
}