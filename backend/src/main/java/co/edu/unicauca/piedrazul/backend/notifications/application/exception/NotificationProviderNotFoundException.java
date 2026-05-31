package co.edu.unicauca.piedrazul.backend.notifications.application.exception;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;

public class NotificationProviderNotFoundException extends RuntimeException {

    public NotificationProviderNotFoundException(
            NotificationChannel channel
    ) {
        super(
                "No existe un provider configurado para el canal "
                        + channel
        );
    }
}