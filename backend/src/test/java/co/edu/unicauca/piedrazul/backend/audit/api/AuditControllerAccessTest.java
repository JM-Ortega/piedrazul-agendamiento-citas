package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.application.AuditQueryService;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuditControllerAccessTest.Config.class)
class AuditControllerAccessTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AuditQueryService auditQueryService() {
            AuditQueryService service = mock(AuditQueryService.class);
            when(service.search(any())).thenReturn(new AuditEventPage(List.of(), 0, 20, 0));
            return service;
        }

        @Bean
        AuditController auditController(AuditQueryService service) {
            return new AuditController(service, new AuditEventMapper());
        }
    }

    @Autowired
    private AuditController controller;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loggedInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", "n/a", "ROLE_" + role));
    }

    private void list() {
        controller.search(null, null, null, null, null, null, null, null, null, 0, 20);
    }

    @Test
    void anAuditorCanListTheAudit() {
        loggedInAs("AUDITOR");

        assertThatCode(this::list).doesNotThrowAnyException();
    }

    @Test
    void everyOtherRoleIsDeniedIncludingAdmin() {
        // La auditoría es exclusiva del rol AUDITOR: ni siquiera ADMIN la consulta.
        for (String role : new String[]{"ADMIN", "DOCTOR", "SCHEDULER", "PATIENT"}) {
            loggedInAs(role);

            assertThatThrownBy(this::list).as("listar como " + role).isInstanceOf(AccessDeniedException.class);
        }
    }
}
