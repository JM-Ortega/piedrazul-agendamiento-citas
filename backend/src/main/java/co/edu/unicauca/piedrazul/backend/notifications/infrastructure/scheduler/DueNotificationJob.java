package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.application.NotificationDispatcherService;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.Notification;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationSchedule;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class DueNotificationJob {

    private static final int BATCH_SIZE = 50;
    private static final Logger log = LoggerFactory.getLogger(DueNotificationJob.class);

    private final ScheduleClaimService scheduleClaimService;
    private final NotificationRepository notificationRepository;
    private final NotificationDispatcherService dispatcherService;
    private final Clock clock;

    public DueNotificationJob(
            ScheduleClaimService scheduleClaimService,
            NotificationRepository notificationRepository,
            NotificationDispatcherService dispatcherService,
            @Qualifier("notificationClock") Clock clock
    ) {
        this.scheduleClaimService = scheduleClaimService;
        this.notificationRepository = notificationRepository;
        this.dispatcherService = dispatcherService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notifications.due-job.fixed-delay:30000}")
    public void processDueNotifications() {
        Instant now = Instant.now(clock);

        List<NotificationSchedule> claimedSchedules =
                scheduleClaimService.claimDueSchedules(now, BATCH_SIZE);

        for (NotificationSchedule schedule : claimedSchedules) {
            Optional<Notification> notification =
                    notificationRepository.findById(schedule.getNotificationId());

            if (notification.isEmpty()) {
                scheduleClaimService.markScheduleFailed(schedule, now);
                continue;
            }

            try {
                dispatcherService.dispatch(
                        notification.get(),
                        schedule,
                        notification.get().getVariables(),
                        now
                );
            } catch (Exception e) {
                log.error("Error inesperado al despachar schedule={}", schedule.getId(), e);
                scheduleClaimService.markScheduleFailed(schedule, now);
            }
        }
    }
}