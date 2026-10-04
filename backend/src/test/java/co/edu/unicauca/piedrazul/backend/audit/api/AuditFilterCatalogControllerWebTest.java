package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.application.AuditFilterCatalogService;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditFilterCatalog;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditActionCatalogEntry;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditModuleCatalogEntry;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP del catálogo que alimenta los filtros de la consulta de auditoría. */
class AuditFilterCatalogControllerWebTest {

    private static final AuditActionCatalogEntry PATIENT_MODIFIED = new AuditActionCatalogEntry(
            AuditAction.PACIENTE_MODIFICADO, "Paciente modificado", "PACIENTES", "Pacientes");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuditFilterCatalogService service = mock(AuditFilterCatalogService.class);
        when(service.filters()).thenReturn(new AuditFilterCatalog(
                List.of(new AuditModuleCatalogEntry("PACIENTES", "Pacientes")),
                List.of(PATIENT_MODIFIED),
                List.of(new AuditFilterCatalog.Option("DENEGADO", "Denegado")),
                90, 100));

        mvc = MockMvcBuilders.standaloneSetup(new AuditFilterCatalogController(service)).build();
    }

    @Test
    void theFiltersCarryTheOptionsOfEveryClosedFilterAndTheLimits() throws Exception {
        mvc.perform(get("/api/audit/catalog/filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[0].code").value("PACIENTES"))
                .andExpect(jsonPath("$.modules[0].name").value("Pacientes"))
                .andExpect(jsonPath("$.actions[0].code").value("PACIENTE_MODIFICADO"))
                .andExpect(jsonPath("$.actions[0].name").value("Paciente modificado"))
                .andExpect(jsonPath("$.actions[0].moduleCode").value("PACIENTES"))
                .andExpect(jsonPath("$.actions[0].moduleName").value("Pacientes"))
                .andExpect(jsonPath("$.outcomes[0].code").value("DENEGADO"))
                .andExpect(jsonPath("$.outcomes[0].name").value("Denegado"))
                .andExpect(jsonPath("$.targetEntityTypes").doesNotExist())
                .andExpect(jsonPath("$.maxRangeDays").value(90))
                .andExpect(jsonPath("$.maxSearchLength").value(100));
    }
}
