package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.appointment.eligibility.AppointmentEligibility;
import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationDispatchException;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ConsoleLogMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.EmailMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.SmsTextMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.WhatsAppTemplateMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.InterruptedDispatchPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.RetryPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore.Begin;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.NotificationDispatchStore.WriteResult;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogContext;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Despacha un schedule ya reclamado. Ciclo de vida por canal:
 *
 * <ol>
 *   <li>elegibilidad actual de notificación y schedule (leída fresca, bajo bloqueo) y, si el
 *       tipo lo exige, de la cita;</li>
 *   <li>proveedor, restricciones de consola (W0a), destinatario y contenido;</li>
 *   <li>intento en curso (PROCESSING) confirmado antes de invocar al proveedor;</li>
 *   <li>vigencia comprobada con un reloj fresco y, si el tipo lo exige, estado confirmado de la
 *       cita leído de nuevo justo antes de la entrega, sin bloqueos sobre el proveedor;</li>
 *   <li>proveedor invocado fuera de toda transacción;</li>
 *   <li>intento, notificación y schedule escritos juntos, con el código eliminado si el despacho
 *       termina.</li>
 * </ol>
 *
 * Cada escritura pasa por {@link NotificationDispatchStore}, que comprueba la propiedad del
 * trabajo; si se perdió, el despachador se detiene sin invocar más proveedores. Un fallo detectado
 * antes del proveedor nunca se registra como aceptación desconocida, y una aceptación (confirmada
 * o posible) nunca se reintenta ni cambia de canal. El estado de la cita decide solo sobre la
 * próxima entrega: una aceptación posible se resuelve antes de mirarlo y una cancelación confirmada
 * después de la última lectura no impide esa entrega ni altera su resultado. El despachador es el
 * único que escribe el resultado de una notificación.
 */
public class NotificationDispatcherService {

    static final String ACCEPTANCE_UNKNOWN = InterruptedDispatchPolicy.ACCEPTANCE_UNKNOWN;
    static final String CONTENT_EXPIRED = "CONTENT_EXPIRED";
    static final String DISPATCH_INTERRUPTED = InterruptedDispatchPolicy.DISPATCH_INTERRUPTED;
    static final String DIAGNOSTIC_REDACTED = "PROVIDER_DIAGNOSTIC_REDACTED";
    static final String APPOINTMENT_NOT_SCHEDULED = "APPOINTMENT_NOT_SCHEDULED";
    static final String TYPE_RETIRED = "NOTIFICATION_TYPE_RETIRED";
    private static final String CONSOLE_CONTENT_WITHHELD = "[contenido oculto: hay un proveedor real habilitado]";

    // Un código de diagnóstico seguro: corto, sin espacios ni caracteres de control.
    private static final Pattern SAFE_CODE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}");
    private static final Pattern SAFE_MESSAGE_ID =
            Pattern.compile("[\\x21-\\x7E]{1,255}");

    private final NotificationDispatchStore store;
    private final ProviderRegistry providerRegistry;
    private final TemplateRenderer templateRenderer;
    private final RetryPolicy retryPolicy;
    private final ChannelFallbackPolicy fallbackPolicy;
    private final RecipientEmailPolicy emailPolicy;
    private final AppointmentEligibility appointmentEligibility;
    private final boolean consoleSensitiveDiagnosticEnabled;
    private final Clock clock;

    /**
     * @param consoleSensitiveDiagnosticEnabled opt-in explícito
     *        ({@code notifications.console.show-sensitive-content}) para mostrar contenido
     *        sensible en la consola de diagnóstico. Solo tiene efecto si no hay ningún proveedor
     *        real registrado.
     */
    public NotificationDispatcherService(
            NotificationDispatchStore store,
            ProviderRegistry providerRegistry,
            TemplateRenderer templateRenderer,
            RetryPolicy retryPolicy,
            ChannelFallbackPolicy fallbackPolicy,
            RecipientEmailPolicy emailPolicy,
            AppointmentEligibility appointmentEligibility,
            boolean consoleSensitiveDiagnosticEnabled,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "El almacén del despacho es obligatorio");
        this.providerRegistry = providerRegistry;
        this.templateRenderer = templateRenderer;
        this.retryPolicy = retryPolicy;
        this.fallbackPolicy = fallbackPolicy;
        this.emailPolicy = emailPolicy;
        this.appointmentEligibility = Objects.requireNonNull(
                appointmentEligibility, "La elegibilidad de citas es obligatoria");
        this.consoleSensitiveDiagnosticEnabled = consoleSensitiveDiagnosticEnabled;
        this.clock = Objects.requireNonNull(clock, "El reloj es obligatorio");
    }

    /**
     * Despacha un schedule reclamado. No propaga errores: un fallo de un schedule no debe
     * abandonar el resto del lote. Lo que no pueda resolverse aquí (p. ej. la base no responde)
     * queda en PROCESSING para la recuperación periódica ({@link InterruptedDispatchPolicy}).
     */
    public void dispatch(ClaimedSchedule claim) {
        Objects.requireNonNull(claim, "El schedule reclamado es obligatorio");

        NotificationLogContext scheduleContext = NotificationLogContext
                .builder(null, null)
                .build()
                .withSchedule(claim.scheduleId());

        Begin begin;

        try {
            begin = store.begin(claim.scheduleId(), claim.attemptCount(), clock.instant());
        } catch (RuntimeException exception) {
            // Nada cambió: el schedule sigue reclamado sin intento en curso.
            NotificationLogger.dispatchInterrupted(scheduleContext, "begin", exception);
            return;
        }

        switch (begin.status()) {
            case OWNERSHIP_LOST -> {
                NotificationLogger.ownershipLost(scheduleContext, "begin");
                return;
            }
            case NOT_DISPATCHABLE -> {
                NotificationLogger.statusUpdated(scheduleContext, "NOT_DISPATCHABLE");
                return;
            }
            case READY -> {
            }
        }

        Run run = new Run(begin, claim.attemptCount());

        try {
            NotificationLogger.dispatchStarted(run.context());
            dispatch(run);
        } catch (RuntimeException exception) {
            NotificationLogger.dispatchInterrupted(run.context(), run.stage, exception);
            abandon(run);
        }
    }

    private void dispatch(Run run) {
        Notification notification = run.notification;

        if (run.attempts.stream().anyMatch(NotificationAttempt::mayHaveBeenAccepted)) {
            resolveInterrupted(run);
            return;
        }

        if (notification.getType().isRetired()) {
            cancel(run, null, TYPE_RETIRED);
            return;
        }

        if (!appointmentStillScheduled(run)) {
            cancel(run, null, APPOINTMENT_NOT_SCHEDULED);
            return;
        }

        if (notification.isExpiredAt(clock.instant())) {
            expire(run, null);
            return;
        }

        List<NotificationChannel> channels = resolveChannels(notification);
        List<NotificationChannel> triedChannels = new ArrayList<>();

        for (int index = 0; index < channels.size(); index++) {
            NotificationChannel channel = channels.get(index);
            Optional<String> skipReason = skipReason(run, channel);

            if (skipReason.isPresent()) {
                NotificationLogger.skipped(run.context(), channel, skipReason.get());
                continue;
            }

            // Tras un canal fallido la cita pudo cancelarse: no se abre un intento innecesario.
            if (!triedChannels.isEmpty() && !appointmentStillScheduled(run)) {
                cancel(run, null, APPOINTMENT_NOT_SCHEDULED);
                return;
            }

            triedChannels.add(channel);

            boolean fallbackAvailable = hasEligibleChannelAfter(run, channels, index);

            if (attemptChannel(run, channel, fallbackAvailable, triedChannels) == Step.DONE) {
                return;
            }
        }

        // Ningún canal pudo intentarse (o el último cedió sin cerrar): se cierra aquí.
        run.stage = "finish";

        if (write(run, DispatchWrite.finishOnly(
                run.scheduleId(), run.expectedAttemptCount, DispatchFinish.FAILED, clock.instant()))) {
            NotificationLogger.failed(run.context(), triedChannels, "Ningún canal disponible");
        }
    }

    private Step attemptChannel(
            Run run,
            NotificationChannel channel,
            boolean fallbackAvailable,
            List<NotificationChannel> triedChannels
    ) {
        Notification notification = run.notification;
        NotificationProvider provider = providerRegistry.getProvider(channel);
        Instant now = clock.instant();

        NotificationAttempt attempt = NotificationAttempt.create(
                notification.getId(),
                run.scheduleId(),
                channel,
                provider.providerName(),
                run.attemptsFor(channel) + 1,
                now
        );

        // Destinatario y contenido se validan antes de cualquier medición del proveedor.
        run.stage = "prepare";
        Prepared prepared = prepare(notification, channel);

        if (prepared.rejection() != null) {
            attempt.rejectBeforeSend(prepared.rejection().code(), prepared.rejection().reason(), now);

            return afterNonAcceptance(
                    run,
                    DispatchWrite.insert(run.scheduleId(), run.expectedAttemptCount, attempt,
                            finishWithoutAcceptance(fallbackAvailable), now),
                    attempt,
                    prepared.rejection().code(),
                    triedChannels
            );
        }

        if (notification.isExpiredAt(now)) {
            expire(run, null);
            return Step.DONE;
        }

        // Ninguna invocación sin un intento en curso confirmado.
        run.stage = "attempt";
        attempt.markProcessing(now);

        if (!write(run, DispatchWrite.insert(
                run.scheduleId(), run.expectedAttemptCount, attempt, DispatchFinish.CONTINUE, now))) {
            return Step.DONE;
        }

        run.inFlight = attempt;
        run.providerInvoked = false;
        NotificationLogger.attemptCreated(run.context(attempt));

        // Último punto controlado antes de entregar al proveedor.
        Instant handoff = clock.instant();

        if (notification.isExpiredAt(handoff)) {
            attempt.markFailed(FailureType.PERMANENT, CONTENT_EXPIRED,
                    "El contenido caducó antes de entregarse al proveedor", handoff);
            expire(run, attempt);
            return Step.DONE;
        }

        // Lectura propia de esta entrega, posterior al commit del intento. Si falla, el proveedor
        // no se invoca y el error sale hacia abandon con providerInvoked en falso.
        if (!appointmentStillScheduled(run)) {
            attempt.cancel(clock.instant());
            cancel(run, attempt, APPOINTMENT_NOT_SCHEDULED);
            return Step.DONE;
        }

        run.stage = "provider";
        run.providerInvoked = true;
        SendOutcome outcome = guardDiagnostics(
                invoke(provider, prepared.message(), notification.getRecipient()), notification);

        run.stage = "resolve";
        return resolve(run, attempt, outcome, fallbackAvailable, triedChannels);
    }

    private Step resolve(
            Run run,
            NotificationAttempt attempt,
            SendOutcome outcome,
            boolean fallbackAvailable,
            List<NotificationChannel> triedChannels
    ) {
        Instant now = clock.instant();

        switch (outcome.outcome()) {
            case ACCEPTED -> {
                attempt.markAccepted(outcome.providerMessageId(), now);

                if (write(run, DispatchWrite.resolve(run.scheduleId(), run.expectedAttemptCount, attempt,
                        DispatchFinish.ACCEPTED, null, now))) {
                    NotificationLogger.dispatched(run.context(attempt), outcome.providerMessageId());
                } else {
                    // Otro actor cerró el trabajo (p. ej. la recuperación, como aceptación
                    // desconocida); el estado no cambia, pero el hecho no se pierde.
                    NotificationLogger.lateAcceptance(run.context(attempt), outcome.providerMessageId());
                }

                return Step.DONE;
            }
            case UNKNOWN -> {
                attempt.markAcceptanceUnknown(ACCEPTANCE_UNKNOWN, outcome.errorMessage(), now);

                if (write(run, DispatchWrite.resolve(run.scheduleId(), run.expectedAttemptCount, attempt,
                        DispatchFinish.FAILED, null, now))) {
                    NotificationLogger.acceptanceUnknown(run.context(attempt), outcome.errorCode());
                }

                return Step.DONE;
            }
            case RETRYABLE -> {
                attempt.markFailed(outcome.failureType(), outcome.errorCode(), outcome.errorMessage(), now);

                Optional<Instant> retryAt = retryPolicy
                        .nextDelay(attempt.getChannel(), outcome.failureType(), attempt.getAttemptNumber() - 1)
                        .map(now::plus)
                        // Un reintento que llegaría con el contenido ya caducado no se programa.
                        .filter(at -> !run.notification.isExpiredAt(at));

                if (retryAt.isPresent()) {
                    if (write(run, DispatchWrite.resolve(run.scheduleId(), run.expectedAttemptCount, attempt,
                            DispatchFinish.RETRY, retryAt.get(), now))) {
                        NotificationLogger.retryScheduled(run.context(attempt), retryAt.get(), outcome.errorCode());
                    }

                    return Step.DONE;
                }
            }
            case REJECTED, PROVIDER_ERROR ->
                    attempt.markFailed(FailureType.PERMANENT, outcome.errorCode(), outcome.errorMessage(), now);
        }

        return afterNonAcceptance(
                run,
                DispatchWrite.resolve(run.scheduleId(), run.expectedAttemptCount, attempt,
                        finishWithoutAcceptance(fallbackAvailable), null, now),
                attempt,
                outcome.errorCode(),
                triedChannels
        );
    }

    /** No aceptación probada: se pasa al siguiente canal elegible o se cierra como FAILED. */
    private Step afterNonAcceptance(
            Run run,
            DispatchWrite write,
            NotificationAttempt attempt,
            String reason,
            List<NotificationChannel> triedChannels
    ) {
        if (!write(run, write)) {
            return Step.DONE;
        }

        NotificationLogger.attemptFailed(run.context(attempt), attempt.getFailureType(), reason);

        if (write.finish() == DispatchFinish.FAILED) {
            NotificationLogger.failed(run.context(), triedChannels, reason);
            return Step.DONE;
        }

        NotificationLogger.fallback(run.context(attempt), attempt.getChannel(), null, reason);
        return Step.NEXT_CHANNEL;
    }

    private static DispatchFinish finishWithoutAcceptance(boolean fallbackAvailable) {
        return fallbackAvailable ? DispatchFinish.CONTINUE : DispatchFinish.FAILED;
    }

    private void expire(Run run, NotificationAttempt inFlightAttempt) {
        run.stage = "expire";
        Instant now = clock.instant();

        DispatchWrite write = inFlightAttempt == null
                ? DispatchWrite.finishOnly(run.scheduleId(), run.expectedAttemptCount, DispatchFinish.EXPIRED, now)
                : DispatchWrite.resolve(run.scheduleId(), run.expectedAttemptCount, inFlightAttempt,
                        DispatchFinish.EXPIRED, null, now);

        if (write(run, write)) {
            NotificationLogger.expired(run.context(), run.notification.expiresAt().orElse(null));
        }
    }

    /** Cierra sin invocar proveedores; los intentos anteriores conservan su resultado. */
    private void cancel(Run run, NotificationAttempt inFlightAttempt, String reason) {
        run.stage = "cancel";
        run.abandonFinish = DispatchFinish.CANCELLED;
        Instant now = clock.instant();

        DispatchWrite write = inFlightAttempt == null
                ? DispatchWrite.finishOnly(run.scheduleId(), run.expectedAttemptCount, DispatchFinish.CANCELLED, now)
                : DispatchWrite.resolve(run.scheduleId(), run.expectedAttemptCount, inFlightAttempt,
                        DispatchFinish.CANCELLED, null, now);

        if (write(run, write)) {
            NotificationLogger.statusUpdated(run.context(), "CANCELLED reason=" + reason);
        }
    }

    /**
     * Estado confirmado de la cita, leído de nuevo en cada llamada. El resultado no se guarda: cada
     * entrega necesita su propia lectura. Un error de lectura se propaga y nunca cuenta como
     * elegible.
     */
    private boolean appointmentStillScheduled(Run run) {
        Notification notification = run.notification;

        if (!notification.getType().requiresScheduledAppointment()) {
            return true;
        }

        run.stage = "eligibility";
        return appointmentEligibility.isScheduled(notification.getAggregate().id());
    }

    /**
     * Trabajo retomado con un intento que pudo llegar al proveedor: nunca se reenvía. Un intento
     * aceptado cierra como aceptado; uno en curso pasa a aceptación desconocida; en ambos casos se
     * elimina el código. Es la misma decisión de {@link InterruptedDispatchPolicy}, por si un
     * trabajo así llegara a reclamarse: la recuperación no reencola nada con un intento en curso.
     */
    private void resolveInterrupted(Run run) {
        run.stage = "interrupted";
        Instant now = clock.instant();

        boolean accepted = run.attempts.stream()
                .anyMatch(attempt -> attempt.mayHaveBeenAccepted()
                        && attempt.getStatus() != AttemptStatus.PROCESSING
                        && attempt.getStatus() != AttemptStatus.UNKNOWN);

        if (accepted) {
            write(run, DispatchWrite.finishOnly(run.scheduleId(), run.expectedAttemptCount, DispatchFinish.ACCEPTED, now));
            return;
        }

        Optional<NotificationAttempt> inFlight = run.attempts.stream()
                .filter(attempt -> attempt.getStatus() == AttemptStatus.PROCESSING)
                .findFirst();

        if (inFlight.isPresent()) {
            NotificationAttempt attempt = inFlight.get();
            attempt.markAcceptanceUnknown(ACCEPTANCE_UNKNOWN, InterruptedDispatchPolicy.INTERRUPTED_MESSAGE, now);

            if (write(run, DispatchWrite.resolve(run.scheduleId(), run.expectedAttemptCount, attempt,
                    DispatchFinish.FAILED, null, now))) {
                NotificationLogger.acceptanceUnknown(run.context(attempt), DISPATCH_INTERRUPTED);
            }

            return;
        }

        write(run, DispatchWrite.finishOnly(run.scheduleId(), run.expectedAttemptCount, DispatchFinish.FAILED, now));
    }

    /**
     * Error inesperado a mitad del despacho. Si el proveedor no llegó a invocarse, se cierra como
     * FAILED (con el intento en curso, si lo hay, marcado como fallido), o como CANCELLED si ese
     * cierre es lo que falló al escribirse. Si pudo invocarse, el intento queda en PROCESSING: la
     * aceptación es posible y solo la recuperación lo resuelve. Si esta escritura también falla, el
     * trabajo queda reclamado para la recuperación, que no distingue este caso y cierra un intento
     * en curso como aceptación desconocida aunque el proveedor no llegara a invocarse.
     */
    private void abandon(Run run) {
        if (run.inFlight != null && run.providerInvoked) {
            return;
        }

        try {
            Instant now = clock.instant();

            if (run.inFlight != null) {
                // Si ya se le había asignado un cierre local (p. ej. caducidad) se conserva.
                if (run.inFlight.getStatus() == AttemptStatus.PROCESSING) {
                    run.inFlight.markFailed(FailureType.PERMANENT, DISPATCH_INTERRUPTED,
                            "Despacho interrumpido antes de invocar al proveedor", now);
                }

                write(run, DispatchWrite.resolve(run.scheduleId(), run.expectedAttemptCount, run.inFlight,
                        run.abandonFinish, null, now));
                return;
            }

            write(run, DispatchWrite.finishOnly(run.scheduleId(), run.expectedAttemptCount, run.abandonFinish, now));
        } catch (RuntimeException exception) {
            NotificationLogger.dispatchInterrupted(run.context(), "abandon", exception);
        }
    }

    private boolean write(Run run, DispatchWrite write) {
        WriteResult result = store.write(write);

        if (result == WriteResult.OWNERSHIP_LOST) {
            NotificationLogger.ownershipLost(run.context(), run.stage);
            return false;
        }

        run.expectedAttemptCount = write.resultingAttemptCount();

        if (write.newAttempt() != null) {
            run.attempts.add(write.newAttempt());
        }

        if (write.resolvedAttempt() != null && write.resolvedAttempt() == run.inFlight) {
            run.inFlight = null;
        }

        return true;
    }

    // ── Elegibilidad de canales ─────────────────────────────────────────

    private Optional<String> skipReason(Run run, NotificationChannel channel) {
        if (!providerRegistry.hasProvider(channel)) {
            return Optional.of("Sin provider registrado");
        }

        Optional<String> consoleBlockReason = sensitiveConsoleBlockReason(run.notification, channel);

        if (consoleBlockReason.isPresent()) {
            return consoleBlockReason;
        }

        boolean permanentlyFailed = run.attempts.stream()
                .anyMatch(attempt -> attempt.getChannel() == channel
                        && attempt.getFailureType() == FailureType.PERMANENT);

        if (permanentlyFailed) {
            return Optional.of("Canal ya rechazado para esta notificación");
        }

        if (run.attemptsFor(channel) >= retryPolicy.maxAttempts(channel)) {
            return Optional.of("Reintentos del canal agotados");
        }

        return Optional.empty();
    }

    private boolean hasEligibleChannelAfter(Run run, List<NotificationChannel> channels, int index) {
        for (int next = index + 1; next < channels.size(); next++) {
            if (skipReason(run, channels.get(next)).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    /**
     * La consola es diagnóstico, no entrega. El contenido sensible (OTP) solo puede llegar a ella
     * sin proveedor real registrado y con el opt-in explícito; con un proveedor real habilitado
     * nunca, aunque el opt-in esté activo o el proveedor real haya fallado. Se evalúa en cada
     * despacho, no al encolar.
     */
    private Optional<String> sensitiveConsoleBlockReason(
            Notification notification,
            NotificationChannel channel
    ) {
        if (channel != NotificationChannel.CONSOLE
                || !notification.getType().containsSensitiveContent()) {
            return Optional.empty();
        }

        if (providerRegistry.hasDeliveryProvider()) {
            return Optional.of("Contenido sensible: la consola no se usa con un proveedor real habilitado");
        }

        if (!consoleSensitiveDiagnosticEnabled) {
            return Optional.of("Contenido sensible: diagnóstico por consola no habilitado");
        }

        return Optional.empty();
    }

    private List<NotificationChannel> resolveChannels(Notification notification) {
        if (notification.getChannelPreference() != null
                && notification.getChannelPreference().preferredOrder() != null
                && !notification.getChannelPreference().preferredOrder().isEmpty()) {
            return notification.getChannelPreference().preferredOrder();
        }

        return fallbackPolicy.resolveChannels(notification.getType());
    }

    // ── Validación local ────────────────────────────────────────────────

    private Prepared prepare(Notification notification, NotificationChannel channel) {
        Optional<LocalRejection> recipientRejection = validateRecipient(channel, notification.getRecipient());

        if (recipientRejection.isPresent()) {
            return new Prepared(null, recipientRejection.get());
        }

        ChannelMessage message;

        try {
            message = templateRenderer.render(notification, channel, notification.getVariables());
        } catch (RuntimeException exception) {
            String code = exception instanceof NotificationDispatchException dispatchException
                    ? dispatchException.getErrorCode()
                    : "TEMPLATE_RENDERING_ERROR";

            return new Prepared(null, new LocalRejection(code,
                    "No se pudo construir el mensaje para el canal " + channel));
        }

        ChannelMessage safeMessage = withoutIdentifiableConsoleContent(notification, message);

        return validateContent(safeMessage)
                .map(rejection -> new Prepared(null, rejection))
                .orElseGet(() -> new Prepared(safeMessage, null));
    }

    /**
     * Un aviso de cita conserva su intento de diagnóstico en la consola, pero con un proveedor real
     * registrado su contenido no llega a ella, aunque el opt-in esté activo: el opt-in solo vale sin
     * proveedor real.
     */
    private ChannelMessage withoutIdentifiableConsoleContent(Notification notification, ChannelMessage message) {
        return message instanceof ConsoleLogMessage
                && notification.getType().containsIdentifiableContent()
                && providerRegistry.hasDeliveryProvider()
                ? new ConsoleLogMessage(CONSOLE_CONTENT_WITHHELD, true)
                : message;
    }

    private Optional<LocalRejection> validateRecipient(NotificationChannel channel, RecipientSnapshot recipient) {
        return switch (channel) {
            case EMAIL -> {
                if (recipient.email() == null || recipient.email().isBlank()) {
                    yield Optional.of(new LocalRejection("RECIPIENT_EMAIL_MISSING", "El destinatario no tiene correo"));
                }

                yield emailPolicy.isUsable(recipient.email())
                        ? Optional.empty()
                        : Optional.of(new LocalRejection("RECIPIENT_EMAIL_INVALID", "El correo del destinatario no es utilizable"));
            }
            case SMS, WHATSAPP -> recipient.phoneE164() == null || recipient.phoneE164().isBlank()
                    ? Optional.of(new LocalRejection("RECIPIENT_PHONE_MISSING", "El destinatario no tiene teléfono"))
                    : Optional.empty();
            case CONSOLE -> Optional.empty();
        };
    }

    /** Un mensaje vacío o con marcadores sin resolver (p. ej. un código ya eliminado) no se envía. */
    private static Optional<LocalRejection> validateContent(ChannelMessage message) {
        boolean usable = switch (message) {
            case EmailMessage email -> isComplete(email.subject())
                    && (isComplete(email.textBody()) || isComplete(email.htmlBody()))
                    && isCompleteOrAbsent(email.textBody())
                    && isCompleteOrAbsent(email.htmlBody());
            case SmsTextMessage sms -> isComplete(sms.text());
            case ConsoleLogMessage console -> isComplete(console.message());
            case WhatsAppTemplateMessage whatsApp -> whatsApp.templateName() != null
                    && !whatsApp.templateName().isBlank();
        };

        return usable
                ? Optional.empty()
                : Optional.of(new LocalRejection("CONTENT_INVALID", "El mensaje está vacío o incompleto"));
    }

    private static boolean isComplete(String text) {
        return text != null && !text.isBlank() && !text.contains("{{");
    }

    private static boolean isCompleteOrAbsent(String text) {
        return text == null || !text.contains("{{");
    }

    // ── Invocación del proveedor ────────────────────────────────────────

    /**
     * Traduce lo que devuelve o lanza el proveedor a un resultado. Una excepción sin evidencia de
     * no aceptación cuenta como aceptación desconocida; la única excepción reconocida como previa
     * a la invocación es el circuit breaker abierto. Nunca lanza {@link RuntimeException}.
     */
    private static SendOutcome invoke(
            NotificationProvider provider,
            ChannelMessage message,
            RecipientSnapshot recipient
    ) {
        NotificationSendResult result;

        try {
            result = provider.send(message, recipient);
        } catch (NotificationDispatchException exception) {
            return fromException(exception);
        } catch (RuntimeException exception) {
            // El mensaje de la excepción no se conserva: puede repetir el contenido enviado.
            return SendOutcome.unknown(exception.getClass().getSimpleName(), null);
        }

        if (result == null) {
            return SendOutcome.unknown("NULL_RESULT", "El proveedor no devolvió resultado");
        }

        return switch (result.outcome()) {
            case ACCEPTED -> new SendOutcome(DeliveryOutcome.ACCEPTED, null, result.providerMessageId(), null, null);
            case RETRYABLE -> new SendOutcome(
                    DeliveryOutcome.RETRYABLE,
                    result.failureType() == FailureType.CIRCUIT_OPEN ? FailureType.CIRCUIT_OPEN : FailureType.TEMPORARY,
                    null,
                    errorCodeOr(result.errorCode(), "PROVIDER_RETRYABLE"),
                    result.errorMessage()
            );
            case REJECTED -> new SendOutcome(DeliveryOutcome.REJECTED, FailureType.PERMANENT, null,
                    errorCodeOr(result.errorCode(), "PROVIDER_REJECTED"), result.errorMessage());
            case PROVIDER_ERROR -> new SendOutcome(DeliveryOutcome.PROVIDER_ERROR, FailureType.PERMANENT, null,
                    errorCodeOr(result.errorCode(), "PROVIDER_ERROR"), result.errorMessage());
            case UNKNOWN -> SendOutcome.unknown(errorCodeOr(result.errorCode(), "PROVIDER_UNKNOWN"),
                    result.errorMessage());
        };
    }

    private static SendOutcome fromException(NotificationDispatchException exception) {
        String code = errorCodeOr(exception.getErrorCode(), "PROVIDER_EXCEPTION");

        if (NotificationDispatchException.CIRCUIT_OPEN.equals(exception.getErrorCode())) {
            return new SendOutcome(DeliveryOutcome.RETRYABLE, FailureType.CIRCUIT_OPEN, null, code,
                    "Circuit breaker abierto: el proveedor no fue invocado");
        }

        DeliveryOutcome evidence = exception.getOutcome();

        if (evidence == null || evidence == DeliveryOutcome.UNKNOWN || evidence == DeliveryOutcome.ACCEPTED) {
            return SendOutcome.unknown(code, null);
        }

        FailureType failureType = evidence == DeliveryOutcome.RETRYABLE ? FailureType.TEMPORARY : FailureType.PERMANENT;
        return new SendOutcome(evidence, failureType, null, code, exception.getMessage());
    }

    /**
     * Frontera entre lo que devuelve el proveedor (no confiable: puede repetir el mensaje enviado,
     * incluido un código de un solo uso) y lo que se guarda en el intento o se escribe en el log.
     *
     * <ul>
     *   <li>El código de error solo se conserva si tiene forma de código y no contiene ningún valor
     *       secreto de la notificación; si no, se sustituye por {@link #DIAGNOSTIC_REDACTED}.</li>
     *   <li>Con contenido sensible, el texto del proveedor nunca se guarda: se usa una descripción
     *       controlada con la clasificación y el código seguro. Sin contenido sensible se conserva,
     *       salvo que contenga un valor secreto.</li>
     *   <li>El id de mensaje del proveedor se descarta si no tiene forma de id o contiene un
     *       secreto.</li>
     * </ul>
     *
     * No altera la clasificación: reintentos y cambios de canal siguen dependiendo solo del
     * resultado.
     */
    private static SendOutcome guardDiagnostics(SendOutcome raw, Notification notification) {
        List<String> secrets = notification.getType().sensitiveVariables().stream()
                .map(notification.getVariables()::get)
                .filter(value -> value != null && !value.isBlank())
                .toList();

        String code = raw.errorCode();

        if (code != null && (!SAFE_CODE.matcher(code).matches() || containsAny(code, secrets))) {
            code = DIAGNOSTIC_REDACTED;
        }

        String message = raw.errorMessage();

        if (message != null
                && (notification.getType().containsSensitiveContent() || containsAny(message, secrets))) {
            message = null;
        }

        if (message == null && raw.outcome() != DeliveryOutcome.ACCEPTED) {
            message = describe(raw.outcome(), raw.failureType(), code);
        }

        String providerMessageId = raw.providerMessageId();

        if (providerMessageId != null
                && (!SAFE_MESSAGE_ID.matcher(providerMessageId).matches() || containsAny(providerMessageId, secrets))) {
            providerMessageId = null;
        }

        return new SendOutcome(raw.outcome(), raw.failureType(), providerMessageId, code, message);
    }

    private static boolean containsAny(String text, List<String> secrets) {
        return secrets.stream().anyMatch(text::contains);
    }

    private static String describe(DeliveryOutcome outcome, FailureType failureType, String code) {
        String suffix = "; código del proveedor: " + (code == null ? "sin código" : code);

        return switch (outcome) {
            case ACCEPTED -> null;
            case UNKNOWN -> "Aceptación no descartable" + suffix;
            case RETRYABLE -> failureType == FailureType.CIRCUIT_OPEN
                    ? "Circuit breaker abierto: el proveedor no fue invocado" + suffix
                    : "No aceptado, fallo transitorio" + suffix;
            case REJECTED -> "Rechazado por el proveedor" + suffix;
            case PROVIDER_ERROR -> "Error del proveedor" + suffix;
        };
    }

    private static String errorCodeOr(String errorCode, String fallback) {
        return errorCode == null || errorCode.isBlank() ? fallback : errorCode;
    }

    // ── Soporte ─────────────────────────────────────────────────────────

    private enum Step {
        DONE,
        NEXT_CHANNEL
    }

    private record LocalRejection(String code, String reason) {
    }

    private record Prepared(ChannelMessage message, LocalRejection rejection) {
    }

    private record SendOutcome(
            DeliveryOutcome outcome,
            FailureType failureType,
            String providerMessageId,
            String errorCode,
            String errorMessage
    ) {
        static SendOutcome unknown(String errorCode, String errorMessage) {
            return new SendOutcome(DeliveryOutcome.UNKNOWN, FailureType.UNKNOWN, null, errorCode, errorMessage);
        }
    }

    /** Estado de un despacho en curso, propio de un único hilo. */
    private static final class Run {
        private final Notification notification;
        private final NotificationSchedule schedule;
        private final List<NotificationAttempt> attempts;
        private int expectedAttemptCount;
        private NotificationAttempt inFlight;
        private boolean providerInvoked;
        private DispatchFinish abandonFinish = DispatchFinish.FAILED;
        private String stage = "start";

        private Run(Begin begin, int claimedAttemptCount) {
            this.notification = begin.notification();
            this.schedule = begin.schedule();
            this.attempts = new ArrayList<>(begin.attempts());
            this.expectedAttemptCount = claimedAttemptCount;
        }

        private UUID scheduleId() {
            return schedule.getId();
        }

        private int attemptsFor(NotificationChannel channel) {
            return (int) attempts.stream().filter(attempt -> attempt.getChannel() == channel).count();
        }

        private NotificationLogContext context() {
            return NotificationLogContext
                    .builder(notification.getId(), notification.getType())
                    .aggregate(notification.getAggregate().type(), notification.getAggregate().id())
                    .build()
                    .withSchedule(schedule.getId());
        }

        private NotificationLogContext context(NotificationAttempt attempt) {
            return context()
                    .withAttempt(attempt.getId(), attempt.getAttemptNumber())
                    .withChannel(attempt.getChannel(), attempt.getProviderName());
        }
    }
}
