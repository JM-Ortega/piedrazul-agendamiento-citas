package co.edu.unicauca.piedrazul.backend.audit.api;

import co.edu.unicauca.piedrazul.backend.audit.application.AuditFilterCatalogService;
import co.edu.unicauca.piedrazul.backend.audit.application.AuditFilterCatalog;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuditFilterCatalogControllerAccessTest.Config.class)
class AuditFilterCatalogControllerAccessTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        AuditFilterCatalogService auditFilterCatalogService() {
            AuditFilterCatalogService service = mock(AuditFilterCatalogService.class);
            when(service.filters()).thenReturn(new AuditFilterCatalog(List.of(), List.of(), List.of(), 90, 100));
            return service;
        }

        @Bean
        AuditFilterCatalogController auditFilterCatalogController(AuditFilterCatalogService service) {
            return new AuditFilterCatalogController(service);
        }
    }

    @Autowired
    private AuditFilterCatalogController controller;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loggedInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", "n/a", "ROLE_" + role));
    }

    @Test
    void anAuditorCanGetTheFilterOptions() {
        loggedInAs("AUDITOR");

        assertThatCode(controller::getFilters).doesNotThrowAnyException();
    }

    @Test
    void everyOtherRoleIsDeniedIncludingAdmin() {
        // Este catálogo solo alimenta los filtros de la consulta de auditoría, exclusiva del rol AUDITOR.
        for (String role : new String[]{"ADMIN", "DOCTOR", "SCHEDULER", "PATIENT"}) {
            loggedInAs(role);

            assertThatThrownBy(controller::getFilters).as("filtros como " + role).isInstanceOf(AccessDeniedException.class);
        }
    }
}
