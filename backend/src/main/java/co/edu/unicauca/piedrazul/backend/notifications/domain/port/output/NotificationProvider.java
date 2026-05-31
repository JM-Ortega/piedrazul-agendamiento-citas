package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;

public interface NotificationProvider {

    NotificationChannel channel();

    String providerName();

    NotificationSendResult send(
            ChannelMessage message,
            RecipientSnapshot recipient
    );
}