package co.edu.unicauca.piedrazul.backend.notifications.domain.policy;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy();

    @ParameterizedTest
    @EnumSource(value = FailureType.class, names = {"TEMPORARY", "CIRCUIT_OPEN"})
    void provenTransientNonAcceptanceIsRetriedWithinBudget(FailureType failureType) {
        assertThat(policy.nextDelay(NotificationChannel.EMAIL, failureType, 0)).contains(Duration.ofSeconds(60));
        assertThat(policy.nextDelay(NotificationChannel.EMAIL, failureType, 1)).contains(Duration.ofSeconds(300));
        assertThat(policy.nextDelay(NotificationChannel.EMAIL, failureType, 2)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = FailureType.class, names = {"PERMANENT", "UNKNOWN"})
    void permanentOrPossiblyAcceptedIsNeverRetried(FailureType failureType) {
        assertThat(policy.nextDelay(NotificationChannel.EMAIL, failureType, 0)).isEmpty();
    }

    @Test
    void nullFailureTypeIsNeverRetried() {
        assertThat(policy.nextDelay(NotificationChannel.EMAIL, null, 0)).isEmpty();
    }

    @Test
    void maxAttemptsCountsInitialAttempt() {
        assertThat(policy.maxAttempts(NotificationChannel.EMAIL)).isEqualTo(3);
        assertThat(policy.maxAttempts(NotificationChannel.SMS)).isEqualTo(2);
        assertThat(policy.maxAttempts(NotificationChannel.CONSOLE)).isEqualTo(1);
    }
}
