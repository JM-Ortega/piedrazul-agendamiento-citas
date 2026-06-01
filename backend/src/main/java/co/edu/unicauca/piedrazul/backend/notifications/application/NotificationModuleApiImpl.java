package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.notifications.NotificationModuleApi;
import co.edu.unicauca.piedrazul.backend.notifications.api.CancellationReason;
import co.edu.unicauca.piedrazul.backend.notifications.api.ScheduleNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.api.SendNotificationCommand;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AggregateReference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationType;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class NotificationModuleApiImpl implements NotificationModuleApi {

    private final NotificationOrchestrator orchestrator;
    private final Clock clock;

    public NotificationModuleApiImpl(
            NotificationOrchestrator orchestrator,
            Clock clock
    ) {
        this.orchestrator = orchestrator;
        this.clock = clock;
    }

    @Override
    public UUID sendNow(SendNotificationCommand command) {
        Objects.requireNonNull(command, "El comando de envío es obligatorio");

        return orchestrator.sendNow(
                command,
                Instant.now(clock)
        );
    }

    @Override
    public UUID schedule(ScheduleNotificationCommand command) {
        Objects.requireNonNull(command, "El comando de programación es obligatorio");

        return orchestrator.schedule(
                command,
                Instant.now(clock)
        );
    }

    @Override
    public void cancelPendingForAggregate(
            AggregateReference aggregate,
            CancellationReason reason
    ) {
        orchestrator.cancelPendingForAggregate(
                aggregate,
                reason,
                Instant.now(clock)
        );
    }

    @Override
    public boolean alreadySent(
            AggregateReference aggregate,
            NotificationType notificationType
    ) {
        return orchestrator.alreadySent(
                aggregate,
                notificationType
        );
    }
}