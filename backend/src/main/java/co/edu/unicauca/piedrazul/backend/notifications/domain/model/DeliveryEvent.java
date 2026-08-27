package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class DeliveryEvent {

    private final UUID id;
    private final UUID attemptId;
    private final String providerName;
    private final String providerMessageId;
    private final String providerEventId;
    private final String eventType;
    private final String rawStatus;
    private final AttemptStatus normalizedStatus;
    private final String payloadJson;
    private final Instant eventTimestamp;
    private final Instant receivedAt;
    private Instant processedAt;

    private DeliveryEvent(
            UUID id,
            UUID attemptId,
            String providerName,
            String providerMessageId,
            String providerEventId,
            String eventType,
            String rawStatus,
            AttemptStatus normalizedStatus,
            String payloadJson,
            Instant eventTimestamp,
            Instant receivedAt,
            Instant processedAt
    ) {
        this.id = Objects.requireNonNull(id, "El id del evento de entrega es obligatorio");
        this.attemptId = Objects.requireNonNull(attemptId, "El id del attempt es obligatorio");
        this.providerName = Objects.requireNonNull(providerName, "El nombre del provider es obligatorio");
        this.providerMessageId = Objects.requireNonNull(providerMessageId, "El id del mensaje del provider es obligatorio");
        this.providerEventId = providerEventId;
        this.eventType = Objects.requireNonNull(eventType, "El tipo de evento es obligatorio");
        this.rawStatus = Objects.requireNonNull(rawStatus, "El estado crudo es obligatorio");
        this.normalizedStatus = Objects.requireNonNull(normalizedStatus, "El estado normalizado es obligatorio");
        this.payloadJson = payloadJson;
        this.eventTimestamp = eventTimestamp;
        this.receivedAt = Objects.requireNonNull(receivedAt, "La fecha de recepción es obligatoria");
        this.processedAt = processedAt;
    }

    public static DeliveryEvent create(
            UUID attemptId,
            String providerName,
            String providerMessageId,
            String providerEventId,
            String eventType,
            String rawStatus,
            AttemptStatus normalizedStatus,
            String payloadJson,
            Instant eventTimestamp,
            Instant receivedAt
    ) {
        return new DeliveryEvent(
                UUID.randomUUID(),
                attemptId,
                providerName,
                providerMessageId,
                providerEventId,
                eventType,
                rawStatus,
                normalizedStatus,
                payloadJson,
                eventTimestamp,
                receivedAt,
                null
        );
    }

    public static DeliveryEvent reconstruct(
            UUID id,
            UUID attemptId,
            String providerName,
            String providerMessageId,
            String providerEventId,
            String eventType,
            String rawStatus,
            AttemptStatus normalizedStatus,
            String payloadJson,
            Instant eventTimestamp,
            Instant receivedAt,
            Instant processedAt
    ) {
        return new DeliveryEvent(
                id,
                attemptId,
                providerName,
                providerMessageId,
                providerEventId,
                eventType,
                rawStatus,
                normalizedStatus,
                payloadJson,
                eventTimestamp,
                receivedAt,
                processedAt
        );
    }

    public void markProcessed(Instant processedAt) {
        this.processedAt = Objects.requireNonNull(
                processedAt,
                "La fecha de procesamiento es obligatoria"
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getRawStatus() {
        return rawStatus;
    }

    public AttemptStatus getNormalizedStatus() {
        return normalizedStatus;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}