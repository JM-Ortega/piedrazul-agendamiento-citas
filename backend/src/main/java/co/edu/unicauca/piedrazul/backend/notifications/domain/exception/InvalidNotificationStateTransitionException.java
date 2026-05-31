package co.edu.unicauca.piedrazul.backend.notifications.domain.exception;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationStatus;

import java.util.Arrays;

public class InvalidNotificationStateTransitionException extends RuntimeException {

    public InvalidNotificationStateTransitionException(
            NotificationStatus currentStatus,
            NotificationStatus[] allowedStatuses
    ) {
        super(
                "Transición inválida para notificación en estado "
                        + currentStatus
                        + ". Estados permitidos: "
                        + Arrays.toString(allowedStatuses)
        );
    }
}