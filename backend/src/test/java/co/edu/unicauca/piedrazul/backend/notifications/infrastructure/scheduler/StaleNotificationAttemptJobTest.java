package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.InterruptedDispatchPolicy.Resolution;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore.Recovery;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** El job delega cada decisión en el almacén y un trabajo fallido no detiene al resto. */
class StaleNotificationAttemptJobTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(15);

    private final NotificationDispatchStore store = mock(NotificationDispatchStore.class);

    private StaleNotificationAttemptJob job(boolean enabled) {
        return new StaleNotificationAttemptJob(store, Clock.fixed(NOW, ZoneOffset.UTC), enabled, THRESHOLD);
    }

    @Test
    void recoversEachInterruptedScheduleWithTheThreshold() {
        UUID scheduleId = UUID.randomUUID();
        when(store.findInterrupted(any(), anyInt())).thenReturn(List.of(scheduleId));
        when(store.recover(any(), any(), any())).thenReturn(new Recovery(Resolution.NONE, null, List.of()));

        job(true).processStaleAttempts();

        verify(store).findInterrupted(NOW.minus(THRESHOLD), 50);
        verify(store).recover(scheduleId, NOW.minus(THRESHOLD), NOW);
    }

    @Test
    void aFailedScheduleDoesNotStopTheRest() {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(store.findInterrupted(any(), anyInt())).thenReturn(List.of(failing, next));
        when(store.recover(eq(failing), any(), any())).thenThrow(new IllegalStateException("fallo sintético"));
        when(store.recover(eq(next), any(), any())).thenReturn(new Recovery(Resolution.NONE, null, List.of()));

        assertThatCode(() -> job(true).processStaleAttempts()).doesNotThrowAnyException();

        verify(store).recover(eq(next), any(), any());
    }

    @Test
    void disabledJobDoesNothing() {
        job(false).processStaleAttempts();

        verifyNoInteractions(store);
    }
}
