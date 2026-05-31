package co.edu.unicauca.piedrazul.backend.notifications.application.exception;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;

public class DuplicateNotificationProviderException extends RuntimeException {

    public DuplicateNotificationProviderException(
            NotificationChannel channel
    ) {
        super(
                "Existen múltiples providers registrados para el canal "
                        + channel
        );
    }
}