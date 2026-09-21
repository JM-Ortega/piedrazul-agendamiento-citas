package co.edu.unicauca.piedrazul.backend.audit;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code audit_event.action_code} tiene una FK al catálogo: una acción del enum
 * sin fila en el catálogo hace fallar el registro de auditoría en ejecución.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditActionCatalogIT extends PostgresIntegrationSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void everyAuditActionHasACatalogRow() {
        List<String> catalog = jdbcTemplate.queryForList("SELECT code FROM piedrazul.audit_action", String.class);

        assertThat(catalog).containsAll(Arrays.stream(AuditAction.values()).map(Enum::name).toList());
    }

    @Test
    void patientModificationBelongsToThePatientsModule() {
        String module = jdbcTemplate.queryForObject(
                "SELECT audit_module_code FROM piedrazul.audit_action WHERE code = 'PACIENTE_MODIFICADO'",
                String.class);

        assertThat(module).isEqualTo("PACIENTES");
    }
}
