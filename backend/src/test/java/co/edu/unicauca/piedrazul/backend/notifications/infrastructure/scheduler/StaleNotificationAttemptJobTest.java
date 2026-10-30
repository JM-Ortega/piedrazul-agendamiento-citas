package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.scheduler;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationAttempt;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationAttemptRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** El job de intentos antiguos solo informa: no reclasifica ningún intento. */
class StaleNotificationAttemptJobTest {

    @Test
    void reportsInFlightAttemptsWithoutChangingState() {
        NotificationAttemptRepository repository = mock(NotificationAttemptRepository.class);
        Instant now = Instant.parse("2026-01-01T12:00:00Z");
        NotificationAttempt inFlight = NotificationAttempt.create(UUID.randomUUID(), UUID.randomUUID(),
                NotificationChannel.EMAIL, "stub", 1, now.minus(Duration.ofHours(3)));
        inFlight.markProcessing(now.minus(Duration.ofHours(3)));
        when(repository.findStaleAttempts(any(), anyInt())).thenReturn(List.of(inFlight));

        new StaleNotificationAttemptJob(repository, Clock.fixed(now, ZoneOffset.UTC), true, Duration.ofHours(2))
                .processStaleAttempts();

        verify(repository).findStaleAttempts(now.minus(Duration.ofHours(2)), 50);
        verify(repository, never()).save(any());
    }
}
