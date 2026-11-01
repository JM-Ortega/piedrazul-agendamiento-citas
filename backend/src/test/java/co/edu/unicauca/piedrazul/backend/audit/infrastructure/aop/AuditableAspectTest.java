package co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop;

import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEvent;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditEventRepository;
import co.edu.unicauca.piedrazul.backend.audit.domain.AuditOutcome;
import co.edu.unicauca.piedrazul.backend.shared.audit.Auditable;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * El aspecto se prueba con seguridad por método real: que registre el rechazo por permisos
 * depende del orden entre ambos, y un aspecto suelto no lo mostraría.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuditableAspectTest.Config.class)
class AuditableAspectTest {

    public static class Target {
        @PreAuthorize("hasRole('ADMIN')")
        @Auditable(action = AuditAction.USUARIO_CREADO, targetEntityType = "Usuario", targetIdExpression = "#id")
        public void everyOutcome(String id, boolean fail) {
            if (fail) throw new IllegalStateException("error de negocio");
        }

        @PreAuthorize("hasRole('ADMIN')")
        @Auditable(action = AuditAction.ROL_ASIGNADO, targetEntityType = "Usuario", targetIdExpression = "#id", onlyDenied = true)
        public void onlyDenied(String id, boolean fail) {
            if (fail) throw new IllegalStateException("error de negocio");
        }

        @PreAuthorize("hasRole('ADMIN')")
        @Auditable(action = AuditAction.USUARIO_CREADO, targetEntityType = "Usuario", targetIdExpression = "#missing.id")
        public void brokenExpression() {
        }

        @PreAuthorize("hasRole('ADMIN')")
        @Auditable(action = AuditAction.USUARIO_CREADO, targetEntityType = "Usuario")
        public void withoutTarget() {
        }
    }

    @Configuration
    @EnableMethodSecurity
    @EnableAspectJAutoProxy
    static class Config {
        @Bean
        AuditEventRepository auditEventRepository() {
            return mock(AuditEventRepository.class);
        }

        @Bean
        Target target() {
            return new Target();
        }

        @Bean
        AuditableAspect auditableAspect(AuditEventRepository repository) {
            return new AuditableAspect(repository, new SecurityContextExtractor());
        }
    }

    @Autowired
    private Target target;

    @Autowired
    private AuditEventRepository repository;

    @BeforeEach
    void resetRepository() {
        reset(repository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loggedInAs(String role) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none")
                .subject("keycloak-user-1")
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private AuditEvent recorded() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    // ---- rechazo por permisos --------------------------------------------------

    @Test
    void aRoleRejectionIsRecordedAsDenegadoWithTheActorAndTheTarget() {
        loggedInAs("DOCTOR");

        assertThatThrownBy(() -> target.everyOutcome("user-9", false)).isInstanceOf(AccessDeniedException.class);

        AuditEvent event = recorded();
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.DENEGADO);
        assertThat(event.getAction()).isEqualTo(AuditAction.USUARIO_CREADO);
        assertThat(event.getActorId()).isEqualTo("keycloak-user-1");
        assertThat(event.getActorRole()).isEqualTo("[DOCTOR]");
        assertThat(event.getTargetEntityType()).isEqualTo("Usuario");
        assertThat(event.getTargetEntityId()).isEqualTo("user-9");
    }

    @Test
    void aRoleRejectionIsRecordedAlsoWhenOnlyDeniedAttemptsAreRequested() {
        loggedInAs("PATIENT");

        assertThatThrownBy(() -> target.onlyDenied("user-9", false)).isInstanceOf(AccessDeniedException.class);

        AuditEvent event = recorded();
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.DENEGADO);
        assertThat(event.getAction()).isEqualTo(AuditAction.ROL_ASIGNADO);
    }

    // ---- solo rechazos ---------------------------------------------------------

    @Test
    void onlyDeniedRecordsNothingWhenTheOperationSucceeds() {
        loggedInAs("ADMIN");

        target.onlyDenied("user-9", false);

        verifyNoInteractions(repository);
    }

    @Test
    void onlyDeniedRecordsNothingWhenTheOperationFailsInternallyAndStillPropagatesTheError() {
        loggedInAs("ADMIN");

        assertThatThrownBy(() -> target.onlyDenied("user-9", true))
                .isInstanceOf(IllegalStateException.class).hasMessage("error de negocio");

        verifyNoInteractions(repository);
    }

    // ---- comportamiento completo (el que ya existía) ---------------------------

    @Test
    void withoutOnlyDeniedSuccessIsRecordedAsExitoso() {
        loggedInAs("ADMIN");

        target.everyOutcome("user-9", false);

        assertThat(recorded().getOutcome()).isEqualTo(AuditOutcome.EXITOSO);
    }

    @Test
    void withoutOnlyDeniedAnInternalFailureIsRecordedAsFallidoAndPropagated() {
        loggedInAs("ADMIN");

        assertThatThrownBy(() -> target.everyOutcome("user-9", true)).isInstanceOf(IllegalStateException.class);

        assertThat(recorded().getOutcome()).isEqualTo(AuditOutcome.FALLIDO);
    }

    // ---- objeto afectado -------------------------------------------------------

    @Test
    void aTargetExpressionThatCannotBeResolvedRecordsNAInsteadOfFailing() {
        loggedInAs("ADMIN");

        target.brokenExpression();

        assertThat(recorded().getTargetEntityId()).isEqualTo("N/A");
    }

    @Test
    void withoutATargetExpressionTheTargetIdIsNA() {
        loggedInAs("ADMIN");

        target.withoutTarget();

        assertThat(recorded().getTargetEntityId()).isEqualTo("N/A");
    }

    @Test
    void theMethodBodyDoesNotRunWhenTheRoleIsRejected() {
        loggedInAs("DOCTOR");

        // Si el cuerpo se ejecutara, fail=true lanzaría IllegalStateException en lugar de AccessDeniedException.
        assertThatThrownBy(() -> target.everyOutcome("user-9", true)).isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(e -> e.getOutcome() != AuditOutcome.DENEGADO));
    }
}
