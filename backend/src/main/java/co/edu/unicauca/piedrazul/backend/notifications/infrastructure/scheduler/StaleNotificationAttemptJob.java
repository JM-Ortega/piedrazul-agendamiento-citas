package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.InterruptedDispatchPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.InterruptedDispatchPolicy.Resolution;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore.Recovery;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogContext;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Recupera el trabajo interrumpido: schedules que siguen reclamados, con o sin intentos, e intentos
 * en curso que nadie resolvió. Cada schedule se resuelve en su propia transacción según
 * {@link InterruptedDispatchPolicy}; repetir la pasada, o ejecutarla en paralelo, no cambia nada
 * más. Nunca invoca proveedores: lo que se reencola lo retoma {@link DueNotificationJob}.
 */
@Component
public class StaleNotificationAttemptJob {

    private static final int BATCH_SIZE = 50;

    private final NotificationDispatchStore store;
    private final Clock clock;
    private final boolean enabled;
    private final Duration threshold;

    public StaleNotificationAttemptJob(
            NotificationDispatchStore store,
            @Qualifier("notificationClock") Clock clock,
            @Value("${notifications.stale-attempt.enabled:true}") boolean enabled,
            @Value("${notifications.stale-attempt.thresholds.default:15m}") Duration threshold
    ) {
        this.store = store;
        this.clock = clock;
        this.enabled = enabled;
        this.threshold = threshold;
    }

    @Scheduled(fixedDelayString = "${notifications.stale-attempt.fixed-delay:300000}")
    public void processStaleAttempts() {
        if (!enabled) {
            return;
        }

        Instant staleBefore = Instant.now(clock).minus(threshold);

        List<UUID> interrupted = store.findInterrupted(staleBefore, BATCH_SIZE);

        for (UUID scheduleId : interrupted) {
            // Un trabajo que no se puede resolver no detiene al resto; se reintenta en la próxima pasada.
            try {
                report(scheduleId, store.recover(scheduleId, staleBefore, Instant.now(clock)));
            } catch (RuntimeException exception) {
                NotificationLogger.dispatchInterrupted(
                        NotificationLogContext.builder(null, null).build().withSchedule(scheduleId),
                        "recovery",
                        exception
                );
            }
        }
    }

    private void report(UUID scheduleId, Recovery recovery) {
        if (recovery.resolution() == Resolution.NONE) {
            return;
        }

        Notification notification = recovery.notification();
        NotificationLogContext context = NotificationLogContext
                .builder(notification.getId(), notification.getType())
                .aggregate(notification.getAggregate().type(), notification.getAggregate().id())
                .build()
                .withSchedule(scheduleId);

        for (NotificationAttempt attempt : recovery.closedAttempts()) {
            NotificationLogger.acceptanceUnknown(
                    context.withAttempt(attempt.getId(), attempt.getAttemptNumber())
                            .withChannel(attempt.getChannel(), attempt.getProviderName()),
                    InterruptedDispatchPolicy.DISPATCH_INTERRUPTED
            );
        }

        NotificationLogger.stale(context, recovery.resolution().name());

        if (recovery.resolution() == Resolution.REQUEUES_EXHAUSTED) {
            NotificationLogger.failed(context, List.of(), InterruptedDispatchPolicy.DISPATCH_INTERRUPTED);
        }
    }
}
