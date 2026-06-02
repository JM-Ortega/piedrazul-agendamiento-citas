package co.edu.unicauca.piedrazul.backend.notifications.domain.policy;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public class RetryPolicy {

    /**
     * @param retriesAlreadyMade número de retries ya realizados para este canal,
     *                           sin contar el intento inicial
     */
    public Optional<Duration> nextDelay(
            NotificationChannel channel,
            FailureType failureType,
            int retriesAlreadyMade
    ) {
        if (failureType == FailureType.PERMANENT || failureType == FailureType.CIRCUIT_OPEN) {
            return Optional.empty();
        }

        List<Duration> delays = delaysFor(channel);

        if (retriesAlreadyMade >= delays.size()) {
            return Optional.empty();
        }

        return Optional.of(delays.get(retriesAlreadyMade));
    }

    private List<Duration> delaysFor(NotificationChannel channel) {
        return switch (channel) {
            case WHATSAPP, SMS -> List.of(
                    Duration.ofSeconds(60)
            );

            case EMAIL -> List.of(
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(300)
            );

            case CONSOLE -> List.of();
        };
    }
}