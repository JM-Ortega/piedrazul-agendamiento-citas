package co.edu.unicauca.piedrazul.backend.notifications.application;

import co.edu.unicauca.piedrazul.backend.notifications.application.exception.NotificationDispatchException;
import co.edu.unicauca.piedrazul.backend.notifications.application.template.TemplateRenderer;
import co.edu.unicauca.piedrazul.backend.notifications.domain.message.ChannelMessage;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.*;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.ChannelFallbackPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.policy.RetryPolicy;
import co.edu.unicauca.piedrazul.backend.notifications.domain.port.output.*;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogContext;
import co.edu.unicauca.piedrazul.backend.notifications.logging.NotificationLogger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class NotificationDispatcherService {

    private final NotificationRepository notificationRepository;
    private final NotificationScheduleRepository scheduleRepository;
    private final NotificationAttemptRepository attemptRepository;
    private final ProviderRegistry providerRegistry;
    private final TemplateRenderer templateRenderer;
    private final RetryPolicy retryPolicy;
    private final ChannelFallbackPolicy fallbackPolicy;

    public NotificationDispatcherService(
            NotificationRepository notificationRepository,
            NotificationScheduleRepository scheduleRepository,
            NotificationAttemptRepository attemptRepository,
            ProviderRegistry providerRegistry,
            TemplateRenderer templateRenderer,
            RetryPolicy retryPolicy,
            ChannelFallbackPolicy fallbackPolicy
    ) {
        this.notificationRepository = notificationRepository;
        this.scheduleRepository = scheduleRepository;
        this.attemptRepository = attemptRepository;
        this.providerRegistry = providerRegistry;
        this.templateRenderer = templateRenderer;
        this.retryPolicy = retryPolicy;
        this.fallbackPolicy = fallbackPolicy;
    }

    public void dispatch(
            Notification notification,
            NotificationSchedule schedule,
            Map<String, String> variables,
            Instant now
    ) {
        Objects.requireNonNull(notification, "La notificación es obligatoria");
        Objects.requireNonNull(schedule, "El schedule es obligatorio");
        Objects.requireNonNull(variables, "Las variables son obligatorias");
        Objects.requireNonNull(now, "La fecha actual es obligatoria");

        NotificationLogContext baseContext = logContext(notification, schedule);

        NotificationLogger.dispatchStarted(baseContext);

        schedule.markProcessing(now);
        scheduleRepository.save(schedule);

        notification.markProcessing(now);
        notificationRepository.save(notification);

        List<NotificationChannel> triedChannels = new ArrayList<>();

        for (NotificationChannel channel : resolveChannels(notification)) {
            if (!providerRegistry.hasProvider(channel)) {
                NotificationLogger.skipped(baseContext, channel, "Sin provider registrado");
                continue;
            }

            triedChannels.add(channel);

            NotificationProvider provider = providerRegistry.getProvider(channel);
            int attemptNumber = nextAttemptNumber(notification, channel);

            NotificationAttempt attempt = NotificationAttempt.create(
                    notification.getId(),
                    schedule.getId(),
                    channel,
                    provider.providerName(),
                    attemptNumber,
                    now
            );

            attempt.markProcessing(now);
            attemptRepository.save(attempt);

            NotificationLogger.attemptCreated(
                    logContext(notification, schedule, attempt)
            );

            DispatchDecision decision;

            try {
                ChannelMessage message = templateRenderer.render(
                        notification,
                        channel,
                        variables
                );

                NotificationSendResult result = provider.send(
                        message,
                        notification.getRecipient()
                );

                decision = handleResult(
                        notification,
                        schedule,
                        attempt,
                        result,
                        triedChannels,
                        attemptNumber,
                        now
                );

            } catch (NotificationDispatchException exception) {
                attempt.markFailed(
                        FailureType.UNKNOWN,
                        exception.getErrorCode(),
                        exception.getMessage(),
                        now
                );

                attemptRepository.save(attempt);

                NotificationLogger.attemptFailed(
                        logContext(notification, schedule, attempt),
                        FailureType.UNKNOWN,
                        exception.getMessage()
                );

                decision = handleFailure(
                        notification,
                        schedule,
                        attempt,
                        FailureType.UNKNOWN,
                        triedChannels,
                        attemptNumber,
                        exception.getMessage(),
                        now
                );
            }

            if (decision == DispatchDecision.STOP
                    || decision == DispatchDecision.RETRY_SCHEDULED) {
                return;
            }
        }

        if (notification.getStatus().isTerminal()) {
            return;
        }

        notification.markFailed(now);
        notificationRepository.save(notification);

        schedule.markFailed(now);
        scheduleRepository.save(schedule);

        NotificationLogger.failed(
                baseContext,
                triedChannels,
                "Todos los canales fallaron"
        );
    }

    private DispatchDecision handleResult(
            Notification notification,
            NotificationSchedule schedule,
            NotificationAttempt attempt,
            NotificationSendResult result,
            List<NotificationChannel> triedChannels,
            int attemptNumber,
            Instant now
    ) {
        Objects.requireNonNull(result, "El resultado del envío es obligatorio");

        AttemptStatus status = result.status();

        if (status == AttemptStatus.SENT) {
            attempt.markSent(result.providerMessageId(), now);
            attemptRepository.save(attempt);

            notification.markAccepted(now);
            notificationRepository.save(notification);

            schedule.markSent(now);
            scheduleRepository.save(schedule);

            NotificationLogger.dispatched(
                    logContext(notification, schedule, attempt),
                    result.providerMessageId()
            );

            return DispatchDecision.STOP;
        }

        if (status == AttemptStatus.ACCEPTED) {
            attempt.markAccepted(result.providerMessageId(), now);
            attemptRepository.save(attempt);

            notification.markAccepted(now);
            notificationRepository.save(notification);

            schedule.markSent(now);
            scheduleRepository.save(schedule);

            NotificationLogger.dispatched(
                    logContext(notification, schedule, attempt),
                    result.providerMessageId()
            );

            return DispatchDecision.STOP;
        }

        if (status == AttemptStatus.DELIVERED || status == AttemptStatus.READ) {
            attempt.markAccepted(result.providerMessageId(), now);

            if (status == AttemptStatus.DELIVERED) {
                attempt.markDelivered(now);
            } else {
                attempt.markRead(now);
            }

            attemptRepository.save(attempt);

            notification.markAccepted(now);
            notification.markDelivered(now);
            notificationRepository.save(notification);

            schedule.markSent(now);
            scheduleRepository.save(schedule);

            NotificationLogger.delivered(
                    logContext(notification, schedule, attempt)
            );

            return DispatchDecision.STOP;
        }

        FailureType failureType = normalizeFailureType(result);

        attempt.markFailed(
                failureType,
                result.errorCode(),
                result.errorMessage(),
                now
        );

        attemptRepository.save(attempt);

        NotificationLogger.attemptFailed(
                logContext(notification, schedule, attempt),
                failureType,
                failureReason(result)
        );

        return handleFailure(
                notification,
                schedule,
                attempt,
                failureType,
                triedChannels,
                attemptNumber,
                failureReason(result),
                now
        );
    }

    private DispatchDecision handleFailure(
            Notification notification,
            NotificationSchedule schedule,
            NotificationAttempt attempt,
            FailureType failureType,
            List<NotificationChannel> triedChannels,
            int attemptNumber,
            String reason,
            Instant now
    ) {
        Optional<Duration> nextDelay = retryPolicy.nextDelay(
                attempt.getChannel(),
                failureType,
                attemptNumber - 1
        );

        if (nextDelay.isPresent()) {
            Instant retryAt = now.plus(nextDelay.get());

            schedule.scheduleRetry(retryAt, now);
            scheduleRepository.save(schedule);

            NotificationLogger.retryScheduled(
                    logContext(notification, schedule, attempt),
                    retryAt,
                    reason
            );

            return DispatchDecision.RETRY_SCHEDULED;
        }

        List<NotificationChannel> channels = resolveChannels(notification);
        int currentIndex = channels.indexOf(attempt.getChannel());

        if (currentIndex >= 0 && currentIndex + 1 < channels.size()) {
            NotificationChannel nextChannel = channels.get(currentIndex + 1);

            NotificationLogger.fallback(
                    logContext(notification, schedule, attempt),
                    attempt.getChannel(),
                    nextChannel,
                    reason
            );

            return DispatchDecision.CONTINUE_TO_NEXT_CHANNEL;
        }

        if (!notification.getStatus().isTerminal()) {
            notification.markFailed(now);
            notificationRepository.save(notification);
        }

        if (!schedule.getStatus().isTerminal()) {
            schedule.markFailed(now);
            scheduleRepository.save(schedule);
        }

        NotificationLogger.failed(
                logContext(notification, schedule),
                triedChannels,
                reason
        );

        return DispatchDecision.STOP;
    }

    private List<NotificationChannel> resolveChannels(Notification notification) {
        if (notification.getChannelPreference() != null
                && notification.getChannelPreference().preferredOrder() != null
                && !notification.getChannelPreference().preferredOrder().isEmpty()) {
            return notification.getChannelPreference().preferredOrder();
        }

        return fallbackPolicy.resolveChannels(notification.getType());
    }

    private NotificationLogContext logContext(
            Notification notification,
            NotificationSchedule schedule
    ) {
        return NotificationLogContext
                .builder(notification.getId(), notification.getType())
                .aggregate(
                        notification.getAggregate().type(),
                        notification.getAggregate().id()
                )
                .build()
                .withSchedule(schedule.getId());
    }

    private NotificationLogContext logContext(
            Notification notification,
            NotificationSchedule schedule,
            NotificationAttempt attempt
    ) {
        return logContext(notification, schedule)
                .withAttempt(attempt.getId(), attempt.getAttemptNumber())
                .withChannel(attempt.getChannel(), attempt.getProviderName());
    }

    private int nextAttemptNumber(
            Notification notification,
            NotificationChannel channel
    ) {
        return attemptRepository.countByNotificationIdAndChannel(
                notification.getId(),
                channel
        ) + 1;
    }

    private FailureType normalizeFailureType(NotificationSendResult result) {
        if (result.failureType() != null) {
            return result.failureType();
        }

        return FailureType.UNKNOWN;
    }

    private String failureReason(NotificationSendResult result) {
        if (result.errorMessage() != null && !result.errorMessage().isBlank()) {
            return result.errorMessage();
        }

        if (result.errorCode() != null && !result.errorCode().isBlank()) {
            return result.errorCode();
        }

        return "Fallo sin detalle del provider";
    }

    private enum DispatchDecision {
        STOP,
        CONTINUE_TO_NEXT_CHANNEL,
        RETRY_SCHEDULED
    }
}