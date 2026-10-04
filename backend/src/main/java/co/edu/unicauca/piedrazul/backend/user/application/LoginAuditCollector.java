package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.events.LoginAttemptedEvent;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import jakarta.ws.rs.NotFoundException;
import org.keycloak.representations.idm.EventRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lee de Keycloak los intentos de inicio de sesión y publica un {@link LoginAttemptedEvent} por
 * cada intento del personal, para que la auditoría los registre.
 *
 * <p>El inicio de sesión ocurre en Keycloak, así que el backend no lo ve: estos eventos son la
 * única fuente. Se descartan:
 * <ul>
 *   <li>Los usuarios que solo tienen el rol PATIENT.</li>
 *   <li>Los intentos con un usuario que no existe: no hay a quién atribuirlos.</li>
 *   <li>Los {@code LOGIN} repetidos de una misma sesión. El {@code check-sso} del frontend
 *       genera uno en cada recarga, idéntico a un inicio de sesión real; solo el primero de cada
 *       sesión lo es.</li>
 * </ul>
 *
 * <p>Lo leído se recuerda en memoria. Tras un reinicio se relee el último día y la auditoría
 * descarta lo que ya había guardado.
 */
@Service
public class LoginAuditCollector {

    static final int PAGE_SIZE = 100;

    private static final Duration INITIAL_LOOKBACK = Duration.ofDays(1);
    private static final int MAX_REMEMBERED = 10_000;
    private static final Set<String> BUSINESS_ROLES = Arrays.stream(Role.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> ONLY_PATIENT = Set.of(Role.PATIENT.name());

    private final KeycloakUserClient keycloakClient;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    // Desde dónde leer en la próxima ejecución (inclusive), en milisegundos.
    private Long cursor;
    // Lo ya procesado, para no publicarlo dos veces: los eventos del borde del cursor
    // vuelven a llegar y las recargas repiten la sesión.
    private final Set<String> processedEventIds = remembered();
    private final Set<String> publishedSessions = remembered();

    @Autowired
    public LoginAuditCollector(KeycloakUserClient keycloakClient, ApplicationEventPublisher publisher) {
        this(keycloakClient, publisher, Clock.systemUTC());
    }

    LoginAuditCollector(KeycloakUserClient keycloakClient, ApplicationEventPublisher publisher, Clock clock) {
        this.keycloakClient = keycloakClient;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Publica los intentos nuevos. Si Keycloak falla a mitad, no publica nada ni avanza: la
     * siguiente ejecución relee el mismo rango.
     */
    @Transactional
    public synchronized void collect() {
        long from = cursor != null ? cursor : clock.instant().minus(INITIAL_LOOKBACK).toEpochMilli();
        long nextCursor = from;
        Set<String> eventIds = new HashSet<>();
        Set<String> sessions = new HashSet<>();
        Map<String, Optional<List<String>>> rolesByUser = new HashMap<>();
        List<LoginAttemptedEvent> attempts = new ArrayList<>();

        for (int first = 0; ; first += PAGE_SIZE) {
            List<EventRepresentation> page = keycloakClient.findLoginEvents(from, first, PAGE_SIZE);

            for (EventRepresentation event : page) {
                nextCursor = Math.max(nextCursor, event.getTime());
                if (processedEventIds.contains(event.getId()) || !eventIds.add(event.getId())) {
                    continue;
                }
                toAttempt(event, rolesByUser, sessions).ifPresent(attempts::add);
            }

            if (page.size() < PAGE_SIZE) {
                break;
            }
        }

        attempts.forEach(publisher::publishEvent);
        processedEventIds.addAll(eventIds);
        publishedSessions.addAll(sessions);
        cursor = nextCursor;
    }

    private Optional<LoginAttemptedEvent> toAttempt(EventRepresentation event,
                                                    Map<String, Optional<List<String>>> rolesByUser,
                                                    Set<String> sessionsInThisRun) {
        if (event.getUserId() == null) {
            return Optional.empty();
        }

        Optional<List<String>> roles = rolesByUser.computeIfAbsent(event.getUserId(), this::businessRoles);
        if (roles.isEmpty() || Set.copyOf(roles.get()).equals(ONLY_PATIENT)) {
            return Optional.empty();
        }

        boolean successful = "LOGIN".equals(event.getType());
        if (successful) {
            String session = event.getSessionId() != null ? event.getSessionId() : event.getId();
            if (publishedSessions.contains(session) || !sessionsInThisRun.add(session)) {
                return Optional.empty();
            }
        }

        return Optional.of(new LoginAttemptedEvent(
                event.getUserId(),
                roles.get().toString(),
                successful,
                event.getId(),
                successful ? event.getSessionId() : null,
                Instant.ofEpochMilli(event.getTime()),
                successful ? null : event.getError()));
    }

    /** Los roles de negocio ordenados, o vacío si la cuenta ya no existe. */
    private Optional<List<String>> businessRoles(String userId) {
        try {
            return Optional.of(keycloakClient.getUserRoles(userId).stream()
                    .filter(BUSINESS_ROLES::contains)
                    .sorted()
                    .toList());
        } catch (NotFoundException deleted) {
            return Optional.empty();
        }
    }

    /** Un conjunto que olvida lo más antiguo al pasar de {@link #MAX_REMEMBERED} elementos. */
    private static Set<String> remembered() {
        return Collections.newSetFromMap(new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_REMEMBERED;
            }
        });
    }
}
