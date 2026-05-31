package co.edu.unicauca.piedrazul.backend.notifications.domain.exception;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;

import java.util.Arrays;

public class InvalidAttemptStateTransitionException extends RuntimeException {

    public InvalidAttemptStateTransitionException(
            AttemptStatus currentStatus,
            AttemptStatus[] allowedStatuses
    ) {
        super(
                "Transición inválida para attempt en estado "
                        + currentStatus
                        + ". Estados permitidos: "
                        + Arrays.toString(allowedStatuses)
        );
    }
}