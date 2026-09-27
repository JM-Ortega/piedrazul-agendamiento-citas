package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.application.AuditEventCriteria;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditQueryService;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventView;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP de la consulta: cómo se leen los parámetros y cómo se responde a los inválidos. */
class AuditControllerWebTest {

    private AuditQueryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AuditQueryService.class);
        when(service.search(any())).thenReturn(new AuditEventPage(List.of(new AuditEventView(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Instant.parse("2026-09-26T17:00:00Z"),
                "kc-1", "[ADMIN, DOCTOR]", "Ana Ruiz", "1002003004",
                "PACIENTE_MODIFICADO", "DENEGADO", "Paciente", "p-9", "corr-1",
                "PACIENTES", "Pacientes")), 0, 20, 1));

        mvc = MockMvcBuilders.standaloneSetup(new AuditController(service, new AuditEventMapper()))
                .setControllerAdvice(new AuditExceptionHandler())
                .build();
    }

    private AuditEventCriteria capturedCriteria() {
        ArgumentCaptor<AuditEventCriteria> captor = ArgumentCaptor.forClass(AuditEventCriteria.class);
        verify(service).search(captor.capture());
        return captor.getValue();
    }

    @Test
    void theResponseCarriesEveryRequestedField() throws Exception {
        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.content[0].timestamp").exists())
                .andExpect(jsonPath("$.content[0].actorName").value("Ana Ruiz"))
                .andExpect(jsonPath("$.content[0].actorUsername").value("1002003004"))
                .andExpect(jsonPath("$.content[0].actorId").value("kc-1"))
                .andExpect(jsonPath("$.content[0].actorRoles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.content[0].actorRoles[1]").value("DOCTOR"))
                .andExpect(jsonPath("$.content[0].action").value("PACIENTE_MODIFICADO"))
                .andExpect(jsonPath("$.content[0].moduleCode").value("PACIENTES"))
                .andExpect(jsonPath("$.content[0].moduleName").value("Pacientes"))
                .andExpect(jsonPath("$.content[0].outcome").value("DENEGADO"))
                .andExpect(jsonPath("$.content[0].targetEntityType").value("Paciente"))
                .andExpect(jsonPath("$.content[0].targetEntityId").value("p-9"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20));
    }

    @Test
    void theStatesBeforeAndAfterAreNeverExposed() throws Exception {
        mvc.perform(get("/api/audit"))
                .andExpect(jsonPath("$.content[0].beforeState").doesNotExist())
                .andExpect(jsonPath("$.content[0].afterState").doesNotExist());
    }

    @Test
    void theCorrelationIdIsNeverExposed() throws Exception {
        mvc.perform(get("/api/audit"))
                .andExpect(jsonPath("$.content[0].correlationId").doesNotExist());
    }

    @Test
    void withoutAnyParameterThereAreNoFiltersAndTheDefaultPageIsUsed() throws Exception {
        mvc.perform(get("/api/audit")).andExpect(status().isOk());

        AuditEventCriteria criteria = capturedCriteria();
        assertThat(criteria.from()).isNull();
        assertThat(criteria.to()).isNull();
        assertThat(criteria.action()).isNull();
        assertThat(criteria.moduleCode()).isNull();
        assertThat(criteria.outcome()).isNull();
        assertThat(criteria.search()).isNull();
        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(20);
    }

    @Test
    void everyFilterIsReadFromItsParameter() throws Exception {
        mvc.perform(get("/api/audit")
                        .param("from", "2026-09-26")
                        .param("to", "2026-09-27")
                        .param("action", "PACIENTE_MODIFICADO")
                        .param("moduleCode", "PACIENTES")
                        .param("outcome", "DENEGADO")
                        .param("search", "  ana   ruiz ")
                        .param("actorId", "kc-1")
                        .param("targetEntityType", "Paciente")
                        .param("targetEntityId", "p-9")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk());

        AuditEventCriteria criteria = capturedCriteria();
        assertThat(criteria.from()).isEqualTo(LocalDate.parse("2026-09-26"));
        assertThat(criteria.to()).isEqualTo(LocalDate.parse("2026-09-27"));
        assertThat(criteria.action()).isEqualTo(AuditAction.PACIENTE_MODIFICADO);
        assertThat(criteria.moduleCode()).isEqualTo("PACIENTES");
        assertThat(criteria.outcome()).isEqualTo(AuditOutcome.DENEGADO);
        assertThat(criteria.search()).isEqualTo("ana ruiz");
        assertThat(criteria.actorId()).isEqualTo("kc-1");
        assertThat(criteria.targetEntityType()).isEqualTo("Paciente");
        assertThat(criteria.targetEntityId()).isEqualTo("p-9");
        assertThat(criteria.page()).isEqualTo(2);
        assertThat(criteria.size()).isEqualTo(10);
    }

    // ---- entradas inválidas: 400, nunca 500 ------------------------------------

    @Test
    void anActionThatDoesNotExistIsABadRequest() throws Exception {
        mvc.perform(get("/api/audit").param("action", "NO_EXISTE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_AUDIT_PARAMETER"));

        verify(service, never()).search(any());
    }

    @Test
    void anOutcomeThatDoesNotExistIsABadRequest() throws Exception {
        mvc.perform(get("/api/audit").param("outcome", "TAL_VEZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_AUDIT_PARAMETER"));
    }

    @Test
    void aMalformedDateIsABadRequest() throws Exception {
        mvc.perform(get("/api/audit").param("from", "ayer"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_AUDIT_PARAMETER"));
    }

    @Test
    void aDateWithTimeIsABadRequest() throws Exception {
        mvc.perform(get("/api/audit").param("from", "2026-09-26T00:00:00-05:00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_AUDIT_PARAMETER"));
    }

    @Test
    void aStartAfterTheEndIsABadRequest() throws Exception {
        mvc.perform(get("/api/audit")
                        .param("from", "2026-09-27")
                        .param("to", "2026-09-26"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_AUDIT_CRITERIA"));

        verify(service, never()).search(any());
    }

    @Test
    void aRangeLongerThanNinetyDaysIsABadRequest() throws Exception {
        mvc.perform(get("/api/audit")
                        .param("from", "2026-01-01")
                        .param("to", "2026-09-26"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("90")));
    }
}
