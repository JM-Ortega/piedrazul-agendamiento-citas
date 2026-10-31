package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.appointment.eligibility.AppointmentEligibility;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.RetryPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.DeliveryOutcome;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationProvider;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationSendResult;
import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.template.InMemoryNotificationTemplateProvider;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

/** Piezas comunes de las pruebas del despachador. Datos exclusivamente sintéticos. */
final class DispatchTestSupport {

    static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    static final String SYNTHETIC_CODE = "000111";
    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private DispatchTestSupport() {
    }

    static NotificationDispatcherService dispatcher(
            InMemoryDispatchStore store,
            Clock clock,
            boolean optIn,
            NotificationProvider... providers
    ) {
        return dispatcher(store, clock, optIn, new TemplateRenderer(new InMemoryNotificationTemplateProvider()), providers);
    }

    static NotificationDispatcherService dispatcher(
            InMemoryDispatchStore store,
            Clock clock,
            boolean optIn,
            TemplateRenderer renderer,
            NotificationProvider... providers
    ) {
        return dispatcher(store, clock, optIn, renderer, appointmentId -> true, providers);
    }

    static NotificationDispatcherService dispatcher(
            InMemoryDispatchStore store,
            Clock clock,
            AppointmentEligibility eligibility,
            NotificationProvider... providers
    ) {
        return dispatcher(store, clock, false, new TemplateRenderer(new InMemoryNotificationTemplateProvider()),
                eligibility, providers);
    }

    private static NotificationDispatcherService dispatcher(
            InMemoryDispatchStore store,
            Clock clock,
            boolean optIn,
            TemplateRenderer renderer,
            AppointmentEligibility eligibility,
            NotificationProvider... providers
    ) {
        return new NotificationDispatcherService(
                store,
                new ProviderRegistry(List.of(providers)),
                renderer,
                new RetryPolicy(),
                new ChannelFallbackPolicy(),
                new RecipientEmailPolicy(VALIDATOR),
                eligibility,
                optIn,
                clock
        );
    }

    static Map<String, String> otpVariables(Instant expiresAt) {
        return Map.of(
                NotificationVariables.CODE, SYNTHETIC_CODE,
                "expirationMinutes", "5",
                "destination", "sintetico@example.test",
                NotificationVariables.EXPIRES_AT, expiresAt.toString()
        );
    }

    static Notification otp() {
        return otp("sintetico@example.test", NOW.plus(Duration.ofMinutes(5)));
    }

    static Notification otp(String email, Instant expiresAt) {
        return notification(NotificationType.OTP_CODE, AggregateType.VERIFICATION, email, otpVariables(expiresAt));
    }

    static Notification appointment() {
        return appointment("sintetico@example.test");
    }

    static Notification appointment(String email) {
        return appointment(NotificationType.APPOINTMENT_REMINDER_2_DAYS, email);
    }

    /** Confirmación histórica, como la que pudo quedar en cola antes de retirar el tipo. */
    static Notification legacyConfirmation() {
        return appointment(NotificationType.APPOINTMENT_SCHEDULED, "sintetico@example.test");
    }

    private static Notification appointment(NotificationType type, String email) {
        return notification(type, AggregateType.APPOINTMENT, email,
                Map.of("patientName", "Paciente Sintético", "doctorName", "Dra. Sintética",
                        "date", "2026-01-10", "time", "08:00"));
    }

    static Notification notification(
            NotificationType type,
            AggregateType aggregateType,
            String email,
            Map<String, String> variables
    ) {
        return Notification.create(
                type,
                new AggregateReference(aggregateType, UUID.randomUUID()),
                new RecipientSnapshot(UUID.randomUUID(), "Sintético", "+570000000000", email,
                        Locale.forLanguageTag("es-CO"), null),
                // Como el orquestador: el orden por defecto queda fijado al encolar
                new ChannelPreference(new ChannelFallbackPolicy().resolveChannels(type)),
                variables,
                NOW
        );
    }

    static NotificationSendResult result(AttemptStatus status, FailureType failureType) {
        return new NotificationSendResult(
                "stub-email",
                NotificationChannel.EMAIL,
                status == AttemptStatus.FAILED ? null : "stub-" + UUID.randomUUID(),
                status,
                failureType,
                failureType == null ? null : "SYNTHETIC_" + failureType,
                failureType == null ? null : "fallo sintético"
        );
    }

    static NotificationSendResult outcome(DeliveryOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> result(AttemptStatus.ACCEPTED, null);
            case RETRYABLE -> result(AttemptStatus.FAILED, FailureType.TEMPORARY);
            case REJECTED -> result(AttemptStatus.FAILED, FailureType.PERMANENT);
            case PROVIDER_ERROR -> new NotificationSendResult("stub-email", NotificationChannel.EMAIL, null,
                    AttemptStatus.FAILED, FailureType.PERMANENT, "SYNTHETIC_CONFIG", "configuración sintética",
                    DeliveryOutcome.PROVIDER_ERROR);
            case UNKNOWN -> new NotificationSendResult("stub-email", NotificationChannel.EMAIL, null,
                    AttemptStatus.UNKNOWN, FailureType.UNKNOWN, "SYNTHETIC_TIMEOUT", "tiempo agotado sintético");
        };
    }

    /** Reloj que solo avanza cuando la prueba lo pide. */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final class StubProvider implements NotificationProvider {
        private final NotificationChannel channel;
        private final Deque<Supplier<NotificationSendResult>> behaviors = new ArrayDeque<>();
        private final boolean qualifiesForOtp;
        final List<ChannelMessage> messages = new ArrayList<>();
        Runnable onSend = () -> {
        };

        StubProvider(NotificationChannel channel, boolean qualifiesForOtp) {
            this.channel = channel;
            this.qualifiesForOtp = qualifiesForOtp;
        }

        static StubProvider email(Supplier<NotificationSendResult> behavior) {
            return new StubProvider(NotificationChannel.EMAIL, false).then(behavior);
        }

        static StubProvider qualifiedEmail(Supplier<NotificationSendResult> behavior) {
            return new StubProvider(NotificationChannel.EMAIL, true).then(behavior);
        }

        /** Comportamientos en orden; el último se repite. */
        StubProvider then(Supplier<NotificationSendResult> behavior) {
            behaviors.addLast(behavior);
            return this;
        }

        int calls() {
            return messages.size();
        }

        @Override
        public boolean qualifiesForOtpDelivery() {
            return qualifiesForOtp;
        }

        @Override
        public NotificationChannel channel() {
            return channel;
        }

        @Override
        public String providerName() {
            return "stub-" + channel.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public NotificationSendResult send(ChannelMessage message, RecipientSnapshot recipient) {
            messages.add(message);
            onSend.run();
            Supplier<NotificationSendResult> behavior = behaviors.size() > 1 ? behaviors.pollFirst() : behaviors.peekFirst();
            return behavior.get();
        }
    }
}
