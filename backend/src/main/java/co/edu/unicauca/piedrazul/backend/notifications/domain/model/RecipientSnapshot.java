package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import java.util.Locale;
import java.util.UUID;

public record RecipientSnapshot(
        UUID recipientId,
        String displayName,
        String phoneE164,
        String email,
        Locale locale,
        ChannelPreference channelPreference
) {
}