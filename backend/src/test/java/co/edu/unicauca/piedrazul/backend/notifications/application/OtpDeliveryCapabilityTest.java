package co.edu.unicauca.piedrazul.backend.notifications.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.notifications.api.OtpDeliveryMode;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.RecipientSnapshot;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationSendResult;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.console.ConsoleNotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.sendgrid.SendGridEmailProvider;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.sendgrid.SendGridProperties;
import com.sendgrid.SendGrid;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qué capacidad de envío de códigos resulta de cada combinación de proveedores, y qué
 * destinatarios se aceptan con cada una. Usa el registro, la política de canales, el proveedor de
 * consola, el proveedor heredado de SendGrid y el validador de Jakarta reales.
 */
class OtpDeliveryCapabilityTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    private static final String PHONE = "3001234567";
    private static final String EMAIL = "ana.ruiz@correo.example.com";

    private final Logger capabilityLogger = (Logger) LoggerFactory.getLogger(OtpDeliveryCapability.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        capabilityLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        capabilityLogger.detachAppender(appender);
    }

    @AfterAll
    static void closeValidator() {
        VALIDATOR_FACTORY.close();
    }

    private static OtpDeliveryCapability capability(boolean consoleOptIn, NotificationProvider... providers) {
        return new OtpDeliveryCapability(
                new ProviderRegistry(List.of(providers)),
                new ChannelFallbackPolicy(),
                consoleOptIn,
                VALIDATOR
        );
    }

    private static ConsoleNotificationProvider console() {
        return new ConsoleNotificationProvider(true);
    }

    /** El proveedor heredado tal como lo registra {@code SendGridConfig}; no se envía nada. */
    private static SendGridEmailProvider sendGrid() {
        return new SendGridEmailProvider(
                new SendGrid("SG.synthetic-test-key"),
                new SendGridProperties(true, "SG.synthetic-test-key", "no-reply@example.com", "Piedrazul", true),
                CircuitBreakerRegistry.ofDefaults()
        );
    }

    private static OtpDeliveryCapability qualifyingEmail() {
        return capability(false, console(), new FakeProvider(NotificationChannel.EMAIL, true));
    }

    // ── Modo ──────────────────────────────────────────────────────────────

    @Test
    void withoutProvidersOrOptInNothingCanBeDelivered() {
        OtpDeliveryCapability capability = capability(false, console());

        assertThat(capability.mode()).isEqualTo(OtpDeliveryMode.UNAVAILABLE);
        assertThat(capability.isUsableRecipient(PHONE, EMAIL)).isFalse();
    }

    @Test
    void withoutAnyProviderTheOptInAloneIsNotEnough() {
        assertThat(capability(true).mode()).isEqualTo(OtpDeliveryMode.UNAVAILABLE);
    }

    @Test
    void consoleWithOptInAndNoRealProviderIsDiagnosticOnly() {
        assertThat(capability(true, console()).mode()).isEqualTo(OtpDeliveryMode.DIAGNOSTIC_CONSOLE);
    }

    @Test
    void theLegacySendGridProviderDoesNotQualifyForCodes() {
        assertThat(sendGrid().qualifiesForOtpDelivery()).isFalse();
        assertThat(capability(false, console(), sendGrid()).mode()).isEqualTo(OtpDeliveryMode.UNAVAILABLE);
    }

    @Test
    void aNonQualifyingRealProviderAlsoRulesOutTheConsoleDiagnostic() {
        // W0a: con un proveedor real registrado, el contenido sensible nunca va a la consola; W0b
        // no puede entonces declarar el diagnóstico disponible.
        assertThat(capability(true, console(), sendGrid()).mode()).isEqualTo(OtpDeliveryMode.UNAVAILABLE);
    }

    @Test
    void aQualifyingEmailProviderEnablesEmailMode() {
        assertThat(qualifyingEmail().mode()).isEqualTo(OtpDeliveryMode.EMAIL);
        assertThat(capability(true, console(), new FakeProvider(NotificationChannel.EMAIL, true)).mode())
                .isEqualTo(OtpDeliveryMode.EMAIL);
    }

    @Test
    void aQualifyingEmailProviderAlongsideANonQualifyingOneIsInconsistent() {
        // El SMS va antes que el correo en la política de códigos: el código acabaría ahí.
        OtpDeliveryCapability capability = capability(false,
                new FakeProvider(NotificationChannel.EMAIL, true),
                new FakeProvider(NotificationChannel.SMS, false));

        assertThat(capability.mode()).isEqualTo(OtpDeliveryMode.UNAVAILABLE);
    }

    @Test
    void phoneChannelsAreNotAnOtpModeYet() {
        assertThat(capability(false, new FakeProvider(NotificationChannel.SMS, true)).mode())
                .isEqualTo(OtpDeliveryMode.UNAVAILABLE);
        assertThat(capability(false, new FakeProvider(NotificationChannel.WHATSAPP, true)).mode())
                .isEqualTo(OtpDeliveryMode.UNAVAILABLE);
    }

    // ── Destinatario en modo correo ──────────────────────────────────────

    @Test
    void aValidEmailIsUsable() {
        assertThat(qualifyingEmail().isUsableRecipient(null, EMAIL)).isTrue();
        assertThat(qualifyingEmail().isUsableRecipient(PHONE, "ana@example.com")).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void aMissingOrBlankEmailIsNotUsableEvenWithAPhone(String email) {
        assertThat(qualifyingEmail().isUsableRecipient(PHONE, email)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ana",
            "ana@",
            "@example.com",
            "ana@@example.com",
            "ana ruiz@example.com",
            "ana@exa mple.com",
            " ana@example.com",
            "ana@example.com "
    })
    void aMalformedEmailIsNotUsable(String email) {
        // Se valida el valor exacto: sin recortar ni normalizar.
        assertThat(qualifyingEmail().isUsableRecipient(null, email)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ana@example.invalid",
            "ana@EXAMPLE.INVALID",
            "ana@clinica.Invalid",
            "ana@sub.domain.example.invalid",
            "ana@invalid",
            "ana@example.invalid."
    })
    void theReservedInvalidDomainIsNotUsable(String email) {
        assertThat(qualifyingEmail().isUsableRecipient(PHONE, email)).isFalse();
    }

    @Test
    void domainsThatOnlyResembleInvalidAreStillUsable() {
        assertThat(qualifyingEmail().isUsableRecipient(null, "ana@invalid.example.com")).isTrue();
        assertThat(qualifyingEmail().isUsableRecipient(null, "ana@notinvalid.com")).isTrue();
    }

    @Test
    void aPhoneAloneIsNotAChannelWhileThereIsNoSmsOrWhatsApp() {
        assertThat(qualifyingEmail().isUsableRecipient(PHONE, null)).isFalse();
    }

    // ── Destinatario en diagnóstico por consola ──────────────────────────

    @Test
    void theDiagnosticAcceptsFictionalRecipients() {
        OtpDeliveryCapability capability = capability(true, console());

        assertThat(capability.isUsableRecipient(null, "ana@example.invalid")).isTrue();
        assertThat(capability.isUsableRecipient("3000000000", null)).isTrue();
        assertThat(capability.isUsableRecipient(null, null)).isFalse();
        assertThat(capability.isUsableRecipient(" ", "")).isFalse();
    }

    // ── Registro al arrancar ─────────────────────────────────────────────

    @Test
    void theUnavailableStateIsReportedOnceWhenResolved() {
        OtpDeliveryCapability capability = capability(false, console());
        capability.mode();
        capability.isUsableRecipient(PHONE, EMAIL);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("503");
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test
    void theDiagnosticIsReportedAsNotDelivery() {
        capability(true, console());

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.getFirst().getFormattedMessage())
                .contains("no se entregan")
                .doesNotContainIgnoringCase("correo");
    }

    private record FakeProvider(NotificationChannel channel, boolean qualifies) implements NotificationProvider {

        @Override
        public String providerName() {
            return "fake-" + channel.name().toLowerCase();
        }

        @Override
        public NotificationSendResult send(ChannelMessage message, RecipientSnapshot recipient) {
            throw new UnsupportedOperationException("No se envía nada en esta prueba");
        }

        @Override
        public boolean qualifiesForOtpDelivery() {
            return qualifies;
        }
    }
}
