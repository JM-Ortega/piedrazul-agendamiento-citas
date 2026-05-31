package co.edu.unicauca.piedrazul.backend.notifications.domain.message;

public record EmailMessage(
        String subject,
        String htmlBody,
        String textBody
) implements ChannelMessage {
}