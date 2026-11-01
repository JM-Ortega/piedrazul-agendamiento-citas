package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence.AuditEventRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.events.LoginAttemptedEvent;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un cliente puede repetir un UUID canónico válido: el id de correlación no identifica el hecho
 * auditado, así que dos hechos distintos con el mismo id deben conservarse en la base real.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditEventRepositoryAdapter.class)
class AuditCorrelationSharingIT extends PostgresIntegrationSupport {

    @Autowired
    private AuditEventRepositoryAdapter repository;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    private Integer rowsWith(String correlationId) {
        entityManager.flush();
        return jdbc.queryForObject(
                "SELECT count(*) FROM piedrazul.audit_event WHERE correlation_id = ?", Integer.class, correlationId);
    }

    @Test
    void twoFactsWithTheSameCorrelationIdAreBothKept() {
        String shared = UUID.randomUUID().toString();
        AuditEventListener listener = new AuditEventListener(repository);

        listener.on(UserCreatedEvent.of("usuario-1", "actor", "[ADMIN]", shared));
        listener.on(UserCreatedEvent.of("usuario-2", "actor", "[ADMIN]", shared));

        assertThat(rowsWith(shared)).isEqualTo(2);
    }

    @Test
    void theSameFactRepeatedWithTheSameIdIsNotDeduplicated() {
        String shared = UUID.randomUUID().toString();
        AuditEventListener listener = new AuditEventListener(repository);

        listener.on(UserCreatedEvent.of("usuario-1", "actor", "[ADMIN]", shared));
        listener.on(UserCreatedEvent.of("usuario-1", "actor", "[ADMIN]", shared));

        assertThat(rowsWith(shared)).isEqualTo(2);
    }

    @Test
    void aLoginIsStillDeduplicatedNextToAFactThatSharesItsSessionId() {
        String session = UUID.randomUUID().toString();
        AuditEventListener listener = new AuditEventListener(repository);
        LoginAttemptedEvent login = new LoginAttemptedEvent(UUID.randomUUID().toString(), "[DOCTOR]", true,
                "evento-1", session, Instant.parse("2026-10-03T14:58:12Z"), null);

        listener.on(UserCreatedEvent.of("usuario-1", "actor", "[ADMIN]", session));
        listener.on(login);
        listener.on(login);

        assertThat(rowsWith(session)).isEqualTo(1);
        assertThat(rowsWith("keycloak-session:" + session)).isEqualTo(1);
    }
}
