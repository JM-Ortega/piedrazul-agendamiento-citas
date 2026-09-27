package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.api.dto.AuditEventResponse;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventMapperTest {

    private final AuditEventMapper mapper = new AuditEventMapper();

    private static AuditEventView view(String roles, String name, String username) {
        return new AuditEventView(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Instant.parse("2026-09-26T12:00:00Z"),
                "kc-1", roles, name, username,
                "PACIENTE_MODIFICADO", "DENEGADO", "Paciente", "p-9", "corr-1");
    }

    @Test
    void everyFieldIsCarriedToTheResponse() {
        AuditEventResponse response = mapper.toResponse(view("[ADMIN, DOCTOR]", "Ana Ruiz", "1002003004"));

        assertThat(response.id()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-09-26T12:00:00Z"));
        assertThat(response.actorName()).isEqualTo("Ana Ruiz");
        assertThat(response.actorUsername()).isEqualTo("1002003004");
        assertThat(response.actorId()).isEqualTo("kc-1");
        assertThat(response.actorRoles()).containsExactly("ADMIN", "DOCTOR");
        assertThat(response.action()).isEqualTo("PACIENTE_MODIFICADO");
        assertThat(response.outcome()).isEqualTo("DENEGADO");
        assertThat(response.targetEntityType()).isEqualTo("Paciente");
        assertThat(response.targetEntityId()).isEqualTo("p-9");
        assertThat(response.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void anActorWithoutPersonKeepsNullNameAndUsername() {
        AuditEventResponse response = mapper.toResponse(view("[ADMIN]", null, null));

        assertThat(response.actorName()).isNull();
        assertThat(response.actorUsername()).isNull();
        assertThat(response.actorId()).isEqualTo("kc-1");
    }

    @Test
    void rolesAreParsedFromTheStoredListText() {
        assertThat(AuditEventMapper.parseRoles("[ADMIN]")).containsExactly("ADMIN");
        assertThat(AuditEventMapper.parseRoles("[PATIENT,DOCTOR]")).containsExactly("PATIENT", "DOCTOR");
        assertThat(AuditEventMapper.parseRoles("  [ SCHEDULER ,  DOCTOR ]  ")).containsExactly("SCHEDULER", "DOCTOR");
    }

    @Test
    void noRolesToRecordMeansAnEmptyList() {
        assertThat(AuditEventMapper.parseRoles(null)).isEqualTo(List.of());
        assertThat(AuditEventMapper.parseRoles("N/A")).isEmpty();
        assertThat(AuditEventMapper.parseRoles("[]")).isEmpty();
        assertThat(AuditEventMapper.parseRoles("")).isEmpty();
    }
}
