package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ConsoleLogMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.AttemptStatus;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.FailureType;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationSendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleNotificationProviderTest {

    private static final String SYNTHETIC_CODE = "000111";

    private final RecipientSnapshot recipient = new RecipientSnapshot(
            UUID.randomUUID(), "Sintético", "+570000000000", null, Locale.forLanguageTag("es-CO"), null);

    private final Logger logger = (Logger) LoggerFactory.getLogger(ConsoleNotificationProvider.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void sensitiveMessageIsMaskedAndNotDeliveredWithoutOptIn() {
        NotificationSendResult result = new ConsoleNotificationProvider(false)
                .send(new ConsoleLogMessage("Código " + SYNTHETIC_CODE, true), recipient);

        assertThat(logs()).noneMatch(line -> line.contains(SYNTHETIC_CODE));
        assertThat(logs()).anyMatch(line -> line.contains("[contenido sensible oculto]"));
        assertDiagnosticOnly(result);
    }

    @Test
    void sensitiveMessageIsShownWithOptInButStillNotDelivered() {
        NotificationSendResult result = new ConsoleNotificationProvider(true)
                .send(new ConsoleLogMessage("Código " + SYNTHETIC_CODE, true), recipient);

        assertThat(logs()).anyMatch(line -> line.contains("[CONSOLE_DIAGNOSTIC]") && line.contains(SYNTHETIC_CODE));
        assertDiagnosticOnly(result);
    }

    @Test
    void nonSensitiveMessageIsPrintedButNeverReportedAsDelivered() {
        NotificationSendResult result = new ConsoleNotificationProvider(false)
                .send(new ConsoleLogMessage("Cita agendada", false), recipient);

        assertThat(logs()).anyMatch(line -> line.contains("[CONSOLE_NOTIFICATION]") && line.contains("Cita agendada"));
        assertDiagnosticOnly(result);
        assertThat(result.status()).isNotEqualTo(AttemptStatus.DELIVERED);
    }

    @Test
    void theDisplayNameIsNotShownInPlaceOfAMaskedContact() {
        RecipientSnapshot withoutContact = new RecipientSnapshot(
                UUID.randomUUID(), "Paciente Sintético", null, null, Locale.forLanguageTag("es-CO"), null);

        new ConsoleNotificationProvider(false).send(new ConsoleLogMessage("Aviso", false), withoutContact);

        assertThat(logs()).noneMatch(line -> line.contains("Paciente Sintético"));
    }

    private void assertDiagnosticOnly(NotificationSendResult result) {
        assertThat(result.status()).isEqualTo(AttemptStatus.FAILED);
        assertThat(result.failureType()).isEqualTo(FailureType.PERMANENT);
        assertThat(result.errorCode()).isEqualTo(ConsoleNotificationProvider.DIAGNOSTIC_ONLY_ERROR_CODE);
        assertThat(result.providerMessageId()).isNull();
        assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PROVIDER_ERROR);
    }

    private List<String> logs() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
