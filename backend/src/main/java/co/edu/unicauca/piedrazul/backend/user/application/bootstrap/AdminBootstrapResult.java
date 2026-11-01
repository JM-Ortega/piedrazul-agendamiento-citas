package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resultado operativo de un intento, pensado para una sola línea de log estructurada
 * ({@link #toLogLine()}) que el despliegue lee. Solo contiene códigos, nombres de
 * propiedad e ids internos: nunca contraseñas, documentos, correos, nombres ni mensajes
 * de excepción (pueden incluir datos).
 */
public record AdminBootstrapResult(
        AdminBootstrapOutcome outcome,
        String attemptId,
        String stage,
        List<String> reasons,
        UUID keycloakUserId,
        UUID personId,
        Map<String, String> observations
) {

    public static final String LOG_MARKER = "identity-bootstrap";

    public AdminBootstrapResult {
        reasons = List.copyOf(reasons);
        observations = Map.copyOf(observations);
    }

    public String toLogLine() {
        StringBuilder line = new StringBuilder(LOG_MARKER)
                .append(" result=").append(outcome)
                .append(" attempt=").append(attemptId)
                .append(" stage=").append(stage);
        if (!reasons.isEmpty()) {
            line.append(" reasons=").append(String.join(",", reasons));
        }
        if (keycloakUserId != null) {
            line.append(" keycloak_user_id=").append(keycloakUserId);
        }
        if (personId != null) {
            line.append(" person_id=").append(personId);
        }
        observations.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> line.append(' ').append(entry.getKey()).append('=').append(entry.getValue()));
        return line.toString();
    }

    static Builder builder(String attemptId, String stage) {
        return new Builder(attemptId, stage);
    }

    static final class Builder {
        private final String attemptId;
        private final String stage;
        private final List<String> reasons = new ArrayList<>();
        private final Map<String, String> observations = new LinkedHashMap<>();
        private UUID keycloakUserId;
        private UUID personId;

        private Builder(String attemptId, String stage) {
            this.attemptId = attemptId;
            this.stage = stage;
        }

        Builder reason(String reason) {
            reasons.add(reason);
            return this;
        }

        Builder reasons(List<String> values) {
            reasons.addAll(values);
            return this;
        }

        Builder account(UUID id) {
            this.keycloakUserId = id;
            return this;
        }

        Builder person(UUID id) {
            this.personId = id;
            return this;
        }

        Builder observe(String key, Object value) {
            observations.put(key, String.valueOf(value));
            return this;
        }

        /** Solo el tipo de la excepción: su mensaje puede contener datos personales. */
        Builder cause(Throwable throwable) {
            return observe("cause", throwable.getClass().getSimpleName());
        }

        AdminBootstrapResult build(AdminBootstrapOutcome outcome) {
            return new AdminBootstrapResult(
                    outcome, attemptId, stage, reasons, keycloakUserId, personId, observations);
        }
    }
}
