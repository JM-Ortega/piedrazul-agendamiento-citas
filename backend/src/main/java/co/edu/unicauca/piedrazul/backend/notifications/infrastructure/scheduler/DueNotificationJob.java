package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.application.NotificationDispatcherService;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.ClaimedSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Component
public class DueNotificationJob {

    private static final int BATCH_SIZE = 50;
    private static final Logger log = LoggerFactory.getLogger(DueNotificationJob.class);

    private final ScheduleClaimService scheduleClaimService;
    private final NotificationDispatcherService dispatcherService;
    private final Clock clock;

    public DueNotificationJob(
            ScheduleClaimService scheduleClaimService,
            NotificationDispatcherService dispatcherService,
            @Qualifier("notificationClock") Clock clock
    ) {
        this.scheduleClaimService = scheduleClaimService;
        this.dispatcherService = dispatcherService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${notifications.due-job.fixed-delay:30000}")
    public void processDueNotifications() {
        Instant now = Instant.now(clock);

        List<ClaimedSchedule> claimedSchedules =
                scheduleClaimService.claimDueSchedules(now, BATCH_SIZE);

        for (ClaimedSchedule claim : claimedSchedules) {
            // El despachador resuelve sus propios errores con escrituras guardadas; esto solo evita
            // que un error imprevisto abandone el resto del lote. No se marca el schedule desde
            // aquí: sería una escritura sin cerca sobre estado posiblemente obsoleto.
            try {
                dispatcherService.dispatch(claim);
            } catch (RuntimeException exception) {
                log.error(
                        "Error inesperado al despachar schedule={} errorType={}",
                        claim.scheduleId(),
                        exception.getClass().getName()
                );
            }
        }
    }
}
