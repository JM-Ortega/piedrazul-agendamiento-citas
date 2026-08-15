package co.edu.unicauca.piedrazul.backend.audit.domain;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de auditoría. Inmutable por diseño: no expone setters
 * ni métodos que permitan mutar el estado tras su creación.
 */
public final class AuditEvent {

    private final UUID id;
    private final Instant timestamp;
    private final String actorUsername;
    private final String actorRole;
    private final AuditAction action;
    private final String targetEntityType;
    private final String targetEntityId;
    private final AuditOutcome outcome;
    private final String correlationId;
    private final String beforeState; // JSON, puede ser null
    private final String afterState;  // JSON, puede ser null

    private AuditEvent(Builder b) {
        this.id = b.id;
        this.timestamp = b.timestamp;
        this.actorUsername = b.actorUsername;
        this.actorRole = b.actorRole;
        this.action = b.action;
        this.targetEntityType = b.targetEntityType;
        this.targetEntityId = b.targetEntityId;
        this.outcome = b.outcome;
        this.correlationId = b.correlationId;
        this.beforeState = b.beforeState;
        this.afterState = b.afterState;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Usar SOLO al reconstruir un AuditEvent ya existente desde persistencia
     * NO usar al crear un evento nuevo: para eso, usa builder().
     */
    public static Builder reconstruct(java.util.UUID id, java.time.Instant timestamp) {
        return new Builder().id(id).timestamp(timestamp);
    }

    // --- getters (sin setters) ---
    public UUID getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getActorUsername() { return actorUsername; }
    public String getActorRole() { return actorRole; }
    public AuditAction getAction() { return action; }
    public String getTargetEntityType() { return targetEntityType; }
    public String getTargetEntityId() { return targetEntityId; }
    public AuditOutcome getOutcome() { return outcome; }
    public String getCorrelationId() { return correlationId; }
    public String getBeforeState() { return beforeState; }
    public String getAfterState() { return afterState; }

    public static class Builder {
        private UUID id = UUID.randomUUID();
        private Instant timestamp = Instant.now();
        private String actorUsername;
        private String actorRole;
        private AuditAction action;
        private String targetEntityType;
        private String targetEntityId;
        private AuditOutcome outcome;
        private String correlationId;
        private String beforeState;
        private String afterState;

        // Privados a propósito: solo accesibles vía reconstruct(),
        // para que nadie los use "por accidente" al crear un evento nuevo.
        private Builder id(UUID id) {
            this.id = id; return this;
        }

        private Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp; return this;
        }

        public Builder actor(String username, String role) {
            this.actorUsername = username;
            this.actorRole = role;
            return this;
        }

        public Builder action(AuditAction action) {
            this.action = action;
            return this;
        }

        public Builder target(String type, String id) {
            this.targetEntityType = type;
            this.targetEntityId = id;
            return this;
        }
        public Builder outcome(AuditOutcome outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder states(String before, String after) {
            this.beforeState = before;
            this.afterState = after;
            return this;
        }

        public AuditEvent build() {
            if (action == null) throw new IllegalStateException("Action es obligatorio");
            if (actorUsername == null) throw new IllegalStateException("Actor es obligatorio");
            if (outcome == null) throw new IllegalStateException("Outcome es obligatorio");
            return new AuditEvent(this);
        }
    }
}