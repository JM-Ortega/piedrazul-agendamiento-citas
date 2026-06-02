package co.edu.unicauca.piedrazul.backend.notifications.domain.exception;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ScheduleStatus;

import java.util.Arrays;

public class InvalidScheduleStateTransitionException extends RuntimeException {

    public InvalidScheduleStateTransitionException(
            ScheduleStatus currentStatus,
            ScheduleStatus[] allowedStatuses
    ) {
        super(
                "Transición inválida para schedule en estado "
                        + currentStatus
                        + ". Estados permitidos: "
                        + Arrays.toString(allowedStatuses)
        );
    }
}