package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.application.NotificationDispatcherService;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.ClaimedSchedule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class DueNotificationJobTest {

    @Test
    void oneFailingScheduleDoesNotAbandonTheRestOfTheBatch() {
        ScheduleClaimService claimService = mock(ScheduleClaimService.class);
        NotificationDispatcherService dispatcher = mock(NotificationDispatcherService.class);
        ClaimedSchedule first = new ClaimedSchedule(UUID.randomUUID(), 0);
        ClaimedSchedule failing = new ClaimedSchedule(UUID.randomUUID(), 0);
        ClaimedSchedule last = new ClaimedSchedule(UUID.randomUUID(), 0);
        when(claimService.claimDueSchedules(any(), anyInt())).thenReturn(List.of(first, failing, last));
        doThrow(new IllegalStateException("fallo sintético")).when(dispatcher).dispatch(failing);

        new DueNotificationJob(claimService, dispatcher,
                Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC)).processDueNotifications();

        verify(dispatcher).dispatch(first);
        verify(dispatcher).dispatch(failing);
        verify(dispatcher).dispatch(last);
        verifyNoMoreInteractions(dispatcher);
    }
}
