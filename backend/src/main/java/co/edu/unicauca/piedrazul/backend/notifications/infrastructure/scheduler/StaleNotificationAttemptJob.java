package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationAttemptRepository;
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

/**
 * Señala intentos en curso (PROCESSING) que nadie resolvió: el proveedor pudo haber sido
 * invocado. Solo informa; no cambia estado. Antes reclasificaba como FAILED intentos aceptados o
 * de aceptación desconocida, lo que contradecía su resultado real. La resolución de este trabajo
 * interrumpido (aceptación desconocida, limpieza del código) corresponde a la recuperación
 * periódica de W1b, que aún no existe.
 */
@Component
public class StaleNotificationAttemptJob {

    private static final int BATCH_SIZE = 50;

    private final NotificationAttemptRepository attemptRepository;
    private final Clock clock;
    private final boolean enabled;
    private final Duration threshold;

    public StaleNotificationAttemptJob(
            NotificationAttemptRepository attemptRepository,
            @Qualifier("notificationClock") Clock clock,
            @Value("${notifications.stale-attempt.enabled:true}") boolean enabled,
            @Value("${notifications.stale-attempt.thresholds.default:2h}") Duration threshold
    ) {
        this.attemptRepository = attemptRepository;
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

        List<NotificationAttempt> staleAttempts =
                attemptRepository.findStaleAttempts(staleBefore, BATCH_SIZE);

        for (NotificationAttempt attempt : staleAttempts) {
            NotificationLogger.stale(
                    NotificationLogContext
                            .builder(attempt.getNotificationId(), null)
                            .build()
                            .withSchedule(attempt.getScheduleId())
                            .withAttempt(
                                    attempt.getId(),
                                    attempt.getAttemptNumber()
                            )
                            .withChannel(
                                    attempt.getChannel(),
                                    attempt.getProviderName()
                            ),
                    "Intento en curso sin resolver (threshold=" + threshold + "); aceptación posible, pendiente de recuperación"
            );
        }
    }
}
