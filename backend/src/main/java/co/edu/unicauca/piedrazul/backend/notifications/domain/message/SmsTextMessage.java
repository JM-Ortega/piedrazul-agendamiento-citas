package co.edu.unicauca.piedrazul.backend.notifications.domain.message;

public record SmsTextMessage(
        String text
) implements ChannelMessage {
}