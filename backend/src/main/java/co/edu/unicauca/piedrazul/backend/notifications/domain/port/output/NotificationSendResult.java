package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;

public record NotificationSendResult(
        String providerName,
        NotificationChannel channel,
        String providerMessageId,
        AttemptStatus status,
        FailureType failureType,
        String errorCode,
        String errorMessage
) {
}