package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Los proveedores que usan el constructor previo obtienen un resultado IC1 deducido con cautela. */
class NotificationSendResultTest {

    @ParameterizedTest(name = "{0}/{1} -> {2}")
    @CsvSource(nullValues = "null", value = {
            "ACCEPTED, null, ACCEPTED",
            "SENT, null, ACCEPTED",
            "DELIVERED, null, ACCEPTED",
            "READ, null, ACCEPTED",
            "FAILED, TEMPORARY, RETRYABLE",
            "FAILED, CIRCUIT_OPEN, RETRYABLE",
            "FAILED, PERMANENT, REJECTED",
            // Sin evidencia explícita de no aceptación: nunca PROVIDER_ERROR, siempre UNKNOWN.
            "FAILED, UNKNOWN, UNKNOWN",
            "FAILED, null, UNKNOWN",
            // BOUNCED y UNDELIVERED presuponen una aceptación previa; CANCELLED no la descarta.
            "BOUNCED, PERMANENT, UNKNOWN",
            "BOUNCED, null, UNKNOWN",
            "UNDELIVERED, PERMANENT, UNKNOWN",
            "UNDELIVERED, TEMPORARY, UNKNOWN",
            "CANCELLED, TEMPORARY, UNKNOWN",
            "CANCELLED, PERMANENT, UNKNOWN",
            "UNKNOWN, null, UNKNOWN",
            "UNKNOWN, TEMPORARY, UNKNOWN",
            "PROCESSING, null, UNKNOWN",
            "PENDING, null, UNKNOWN",
            "null, null, UNKNOWN"
    })
    void legacyConstructorDerivesOutcome(AttemptStatus status, FailureType failureType, DeliveryOutcome expected) {
        NotificationSendResult result = new NotificationSendResult(
                "stub", NotificationChannel.EMAIL, null, status, failureType, null, null);

        assertThat(result.outcome()).isEqualTo(expected);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(AttemptStatus.class)
    void providerErrorIsNeverInferred(AttemptStatus status) {
        for (FailureType failureType : FailureType.values()) {
            assertThat(new NotificationSendResult("stub", NotificationChannel.EMAIL, null, status, failureType, null, null)
                    .outcome()).isNotEqualTo(DeliveryOutcome.PROVIDER_ERROR);
        }

        assertThat(new NotificationSendResult("stub", NotificationChannel.EMAIL, null, status, null, null, null)
                .outcome()).isNotEqualTo(DeliveryOutcome.PROVIDER_ERROR);
    }

    @Test
    void explicitOutcomeWins() {
        NotificationSendResult result = new NotificationSendResult("stub", NotificationChannel.EMAIL, null,
                AttemptStatus.FAILED, FailureType.PERMANENT, "CONFIG", null, DeliveryOutcome.PROVIDER_ERROR);

        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
    }
}
