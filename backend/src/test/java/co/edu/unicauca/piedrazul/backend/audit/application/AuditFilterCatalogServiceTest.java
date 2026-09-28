package co.edu.unicauca.piedrazul.backend.audit.application;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogEntry;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModuleCatalogEntry;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.shared.audit.AuditTargetType;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditFilterCatalogServiceTest {

    private AuditFilterCatalog filters;

    @BeforeEach
    void setUp() {
        AuditActionCatalogRepository repository = mock(AuditActionCatalogRepository.class);
        when(repository.findAllModules()).thenReturn(List.of(
                new AuditModuleCatalogEntry("USUARIOS", "Usuarios"),
                new AuditModuleCatalogEntry("CITAS", "Citas"),
                new AuditModuleCatalogEntry("PACIENTES", "Pacientes")));
        when(repository.findAll()).thenReturn(List.of(
                new AuditActionCatalogEntry(AuditAction.USUARIO_CREADO, "Usuario creado", "USUARIOS", "Usuarios"),
                new AuditActionCatalogEntry(AuditAction.ROL_ASIGNADO, "Rol asignado", "USUARIOS", "Usuarios"),
                new AuditActionCatalogEntry(AuditAction.CITA_AGENDADA, "Cita agendada", "CITAS", "Citas")));

        filters = new AuditFilterCatalogService(repository).filters();
    }

    @Test
    void modulesAreSortedByName() {
        assertThat(filters.modules()).extracting(AuditModuleCatalogEntry::code)
                .containsExactly("CITAS", "PACIENTES", "USUARIOS");
    }

    @Test
    void actionsAreSortedByModuleNameAndThenByName() {
        assertThat(filters.actions()).extracting(AuditActionCatalogEntry::code)
                .containsExactly(AuditAction.CITA_AGENDADA, AuditAction.ROL_ASIGNADO, AuditAction.USUARIO_CREADO);
    }

    @Test
    void everyOutcomeIsOfferedWithItsDisplayName() {
        assertThat(filters.outcomes()).extracting(AuditFilterCatalog.Option::code)
                .containsExactlyElementsOf(Arrays.stream(AuditOutcome.values()).map(Enum::name).toList());
        assertThat(filters.outcomes()).contains(new AuditFilterCatalog.Option("DENEGADO", "Denegado"));
    }

    @Test
    void everyTargetEntityTypeIsOfferedWithItsDisplayName() {
        assertThat(filters.targetEntityTypes()).extracting(AuditFilterCatalog.Option::code)
                .containsExactlyElementsOf(AuditTargetType.NAMES.keySet());
        assertThat(filters.targetEntityTypes())
                .contains(new AuditFilterCatalog.Option(AuditTargetType.CONTROL_MEDICO, "Control médico"));
    }

    @Test
    void theLimitsAreTheOnesTheQueryValidates() {
        assertThat(filters.maxRangeDays()).isEqualTo(AuditEventCriteria.MAX_RANGE_DAYS);
        assertThat(filters.maxSearchLength()).isEqualTo(AuditEventCriteria.MAX_SEARCH_LENGTH);
    }
}
