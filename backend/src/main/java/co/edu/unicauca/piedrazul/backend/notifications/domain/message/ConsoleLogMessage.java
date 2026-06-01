package co.edu.unicauca.piedrazul.backend.notifications.domain.message;

public record ConsoleLogMessage(
        String message,
        boolean sensitive
) implements ChannelMessage {
}