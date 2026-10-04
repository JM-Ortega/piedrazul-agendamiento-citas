package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventQuery;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventView;
import co.edu.unicauca.piedrazul.backend.audit.infrastructure.persistence.AuditEventRepositoryAdapter;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.events.LoginAttemptedEvent;
import org.junit.jupiter.api.BeforeEach;
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
 * Los inicios de sesión del personal sobre PostgreSQL real: se guardan con la hora de Keycloak,
 * aparecen en la consulta de auditoría bajo Seguridad y no se duplican al releerlos.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditEventRepositoryAdapter.class, AuditEventListener.class})
class LoginAuditIT extends PostgresIntegrationSupport {

    private static final Instant OCCURRED_AT = Instant.parse("2031-06-01T14:58:12.345Z");

    @Autowired
    private AuditEventListener listener;

    @Autowired
    private AuditEventRepositoryAdapter repository;

    @Autowired
    private JdbcTemplate jdbc;

    private String account;
    private String session;

    @BeforeEach
    void setUp() {
        account = UUID.randomUUID().toString();
        session = "s-" + UUID.randomUUID();
        String document = String.valueOf(System.nanoTime()).substring(0, 10);
        jdbc.update("""
                INSERT INTO piedrazul.person (id, user_id, identification_type, identification, first_name, last_name, phone, email)
                VALUES (?, ?, 'CEDULA', ?, 'Ana', 'Ruiz', '3001234567', ?)
                """, UUID.randomUUID(), UUID.fromString(account), document, document + "@example.com");
    }

    private AuditEventPage loginsOfTheAccount() {
        return repository.findByCriteria(new AuditEventQuery(
                null, null, null, "SEGURIDAD", null, null, account, 0, 50));
    }

    @Test
    void aSuccessfulLoginIsListedUnderSecurityWithTheTimeFromKeycloak() {
        listener.on(new LoginAttemptedEvent(account, "[DOCTOR]", true, "e-1", session, OCCURRED_AT, null));

        AuditEventPage page = loginsOfTheAccount();

        assertThat(page.content()).hasSize(1);
        AuditEventView view = page.content().getFirst();
        assertThat(view.action()).isEqualTo("LOGIN_EXITOSO");
        assertThat(view.outcome()).isEqualTo("EXITOSO");
        assertThat(view.timestamp()).isEqualTo(OCCURRED_AT);
        assertThat(view.moduleName()).isEqualTo("Seguridad");
        assertThat(view.actorName()).isEqualTo("Ana Ruiz");
    }

    @Test
    void readingTheSameEventsAgainDoesNotDuplicateThem() {
        LoginAttemptedEvent login = new LoginAttemptedEvent(account, "[DOCTOR]", true, "e-1", session, OCCURRED_AT, null);
        LoginAttemptedEvent failure = new LoginAttemptedEvent(account, "[DOCTOR]", false,
                "e-" + UUID.randomUUID(), null, OCCURRED_AT.minusSeconds(30), "invalid_user_credentials");

        listener.on(failure);
        listener.on(login);
        listener.on(failure);
        listener.on(login);

        assertThat(loginsOfTheAccount().content())
                .extracting(AuditEventView::action)
                .containsExactly("LOGIN_EXITOSO", "LOGIN_FALLIDO");
    }
}
