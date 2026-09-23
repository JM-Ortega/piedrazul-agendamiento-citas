package co.edu.unicauca.piedrazul.backend.audit;

import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La bitácora de auditoría es append-only. V001 lo impone con permisos del rol de la
 * aplicación (recibido por el placeholder {@code app_role}) y con un trigger. Este test
 * comprueba que la migración real, con el nombre del rol sustituido, deja al rol
 * exactamente con SELECT e INSERT.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditEventImmutabilityIT extends PostgresIntegrationSupport {

    private static final String TABLE = "piedrazul.audit_event";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private boolean can(String privilege) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege(?, ?, ?)", Boolean.class, APP_ROLE, TABLE, privilege));
    }

    @Test
    void applicationRoleCanOnlyReadAndAppend() {
        assertThat(can("SELECT")).as("SELECT").isTrue();
        assertThat(can("INSERT")).as("INSERT").isTrue();
        assertThat(can("UPDATE")).as("UPDATE").isFalse();
        assertThat(can("DELETE")).as("DELETE").isFalse();
    }

    private void insertEvent() {
        jdbcTemplate.update("""
                INSERT INTO piedrazul.audit_event (id, occurred_at, actor_id, action_code, outcome)
                VALUES (gen_random_uuid(), now(), 'actor', 'LOGIN_EXITOSO', 'EXITOSO')
                """);
    }

    // Cada operación va en su test: tras el primer error PostgreSQL aborta la transacción
    // y la siguiente sentencia fallaría por eso, no por el trigger.
    @Test
    void theTriggerRejectsUpdatesEvenForTheOwner() {
        insertEvent();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE piedrazul.audit_event SET actor_id = 'otro'"))
                .hasMessageContaining("append-only");
    }

    @Test
    void theTriggerRejectsDeletesEvenForTheOwner() {
        insertEvent();

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM piedrazul.audit_event"))
                .hasMessageContaining("append-only");
    }
}
