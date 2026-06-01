package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import java.util.List;

public record ChannelPreference(
        List<NotificationChannel> preferredOrder
) {
}