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

    /**
     * Indica si el proveedor es un canal real apto para entregar códigos de verificación. Por
     * defecto no lo es: un proveedor debe declararlo de forma explícita. Ningún proveedor
     * heredado (p. ej. SendGrid) lo declara; la entrega real de códigos queda pendiente del
     * proveedor de correo definitivo (SES).
     */
    default boolean qualifiesForOtpDelivery() {
        return false;
    }
}