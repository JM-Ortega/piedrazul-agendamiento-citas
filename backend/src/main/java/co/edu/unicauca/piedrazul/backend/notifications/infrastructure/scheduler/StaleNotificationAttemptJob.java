package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationAttemptRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationRepository;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationScheduleRepository;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogContext;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class StaleNotificationAttemptJob {

    private static final int BATCH_SIZE = 50;

    private final NotificationAttemptRepository attemptRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationScheduleRepository scheduleRepository;
    private final Clock clock;
    private final boolean enabled;
    private final Duration threshold;

    public StaleNotificationAttemptJob(
            NotificationAttemptRepository attemptRepository,
            NotificationRepository notificationRepository,
            NotificationScheduleRepository scheduleRepository,
            @Qualifier("notificationClock") Clock clock,
            @Value("${notifications.stale-attempt.enabled:true}") boolean enabled,
            @Value("${notifications.stale-attempt.thresholds.default:2h}") Duration threshold
    ) {
        this.attemptRepository = attemptRepository;
        this.notificationRepository = notificationRepository;
        this.scheduleRepository = scheduleRepository;
        this.clock = clock;
        this.enabled = enabled;
        this.threshold = threshold;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${notifications.stale-attempt.fixed-delay:300000}")
    public void processStaleAttempts() {
        if (!enabled) {
            return;
        }

        Instant now = Instant.now(clock);
        Instant staleBefore = now.minus(threshold);

        // Primer corte: usa threshold default para todos los canales.
        // Pendiente: aplicar thresholds por canal desde notifications.stale-attempt.thresholds.
        List<NotificationAttempt> staleAttempts =
                attemptRepository.findStaleAttempts(staleBefore, BATCH_SIZE);

        for (NotificationAttempt attempt : staleAttempts) {
            if (attempt.getStatus().isTerminal()) {
                continue;
            }

            attempt.markFailed(
                    FailureType.UNKNOWN,
                    "STALE_ATTEMPT",
                    "Attempt sin actualización antes de " + staleBefore,
                    now
            );

            attemptRepository.save(attempt);

            scheduleRepository.findById(attempt.getScheduleId()).ifPresent(schedule -> {
                if (!schedule.getStatus().isTerminal()) {
                    schedule.markFailed(now);
                    scheduleRepository.save(schedule);
                }
            });

            notificationRepository.findById(attempt.getNotificationId()).ifPresent(notification -> {
                if (!notification.getStatus().isTerminal()) {
                    notification.markFailed(now);
                    notificationRepository.save(notification);
                }
            });

            NotificationLogger.stale(
                    NotificationLogContext
                            .builder(attempt.getNotificationId(), null)
                            .build()
                            .withAttempt(
                                    attempt.getId(),
                                    attempt.getAttemptNumber()
                            )
                            .withChannel(
                                    attempt.getChannel(),
                                    attempt.getProviderName()
                            ),
                    "threshold=" + threshold
            );
        }
    }
}