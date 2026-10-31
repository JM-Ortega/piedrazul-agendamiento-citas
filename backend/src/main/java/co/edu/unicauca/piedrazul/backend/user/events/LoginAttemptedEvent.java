package co.edu.unicauca.piedrazul.backend.user.events;

import java.time.Instant;

/**
 * Un intento de inicio de sesión de alguien del personal, leído de los eventos de Keycloak. Los
 * pacientes no se publican.
 *
 * @param userId          id de la cuenta en Keycloak
 * @param roles           roles de negocio de la cuenta, p. ej. {@code [DOCTOR, SCHEDULER]}
 * @param successful      si el inicio de sesión fue exitoso
 * @param keycloakEventId id del evento en Keycloak
 * @param sessionId       sesión de Keycloak que abrió el inicio de sesión; nulo si falló
 * @param occurredAt      cuándo ocurrió en Keycloak
 * @param error           motivo del fallo según Keycloak (p. ej. {@code invalid_user_credentials}); nulo si fue exitoso
 */
public record LoginAttemptedEvent(
        String userId,
        String roles,
        boolean successful,
        String keycloakEventId,
        String sessionId,
        Instant occurredAt,
        String error
) {
}
