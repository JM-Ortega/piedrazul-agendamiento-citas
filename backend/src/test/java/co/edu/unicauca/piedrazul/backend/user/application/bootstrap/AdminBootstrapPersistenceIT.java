package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.AccountCreationResult;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.PersonRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bootstrap contra PostgreSQL real (Keycloak simulado): exclusión del bloqueo consultivo
 * entre sesiones, Person confirmada y vinculada, y un segundo arranque que no escribe.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        AdminBootstrapService.class,
        AdminBootstrapInputValidator.class,
        PostgresAdminBootstrapLock.class,
        PersonExternalServiceImp.class,
        AdminBootstrapPersistenceIT.TestBeans.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminBootstrapPersistenceIT extends PostgresIntegrationSupport {

    private static final String USERNAME = "1061234567";
    private static final String EMAIL = "ana.prueba@example.test";
    private static final UUID ACCOUNT = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @TestConfiguration
    static class TestBeans {
        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    @MockitoBean
    private KeycloakUserClient keycloak;
    @MockitoBean
    private KeycloakUserService keycloakUserService;

    @Autowired
    private AdminBootstrapService service;
    @Autowired
    private PostgresAdminBootstrapLock lock;
    @Autowired
    private PersonRepository personRepository;

    @BeforeEach
    void clean() {
        personRepository.deleteAll();
        reset(keycloak);
    }

    private static AdminBootstrapRequest request() {
        return new AdminBootstrapRequest(USERNAME, "CEDULA", "Ana", "Prueba", EMAIL, "3000000000", "Temp-Bootstrap-Value-2026!");
    }

    private static UserRepresentation account(boolean enabled) {
        UserRepresentation user = new UserRepresentation();
        user.setId(ACCOUNT.toString());
        user.setUsername(USERNAME);
        user.setEnabled(enabled);
        user.setEmailVerified(false);
        user.setRequiredActions(List.of("UPDATE_PASSWORD"));
        return user;
    }

    @Test
    void advisoryLockExcludesAConcurrentAttemptUntilReleased() {
        Optional<AdminBootstrapLock.Held> first = lock.tryAcquire();
        assertThat(first).isPresent();

        // Otra sesión (otra conexión del pool) no lo obtiene
        assertThat(lock.tryAcquire()).isEmpty();

        first.get().close();

        Optional<AdminBootstrapLock.Held> again = lock.tryAcquire();
        assertThat(again).isPresent();
        again.get().close();
    }

    @Test
    void concurrentAttemptIsBusyAndWritesNothing() {
        try (AdminBootstrapLock.Held held = lock.tryAcquire().orElseThrow()) {
            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(AdminBootstrapOutcome.BUSY);
            verify(keycloak, never()).createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any());
        }
        assertThat(personRepository.count()).isZero();
    }

    @Test
    void cleanCreationCommitsALinkedPersonAndASecondRunWritesNothing() {
        when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.empty());
        when(keycloak.findUserByEmail(EMAIL)).thenReturn(Optional.empty());
        when(keycloak.findUsersByRole(Role.ADMIN)).thenReturn(List.of());
        when(keycloak.createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any()))
                .thenReturn(new AccountCreationResult.Created(ACCOUNT));
        when(keycloak.findUserById(ACCOUNT))
                .thenReturn(Optional.of(account(false)))
                .thenReturn(Optional.of(account(true)));
        when(keycloak.getEffectiveRealmRoles(ACCOUNT)).thenReturn(List.of("ADMIN"));
        when(keycloak.hasPasswordCredential(ACCOUNT)).thenReturn(true);

        AdminBootstrapResult created = service.bootstrap(request());

        assertThat(created.outcome()).isEqualTo(AdminBootstrapOutcome.CREATED);
        Person person = personRepository.findByIdentification(USERNAME).orElseThrow();
        assertThat(person.getUserId()).isEqualTo(ACCOUNT);
        assertThat(person.getIdentificationType()).isEqualTo(IdentificationType.CEDULA);
        assertThat(person.getEmail()).isEqualTo(EMAIL);
        assertThat(created.personId()).isEqualTo(person.getId());

        // Segundo arranque: la cuenta existe y la Person (real) la vincula
        reset(keycloak);
        when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.of(account(true)));
        when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.of(account(true)));
        when(keycloak.getEffectiveRealmRoles(ACCOUNT)).thenReturn(List.of());

        AdminBootstrapResult second = service.bootstrap(request());

        assertThat(second.outcome()).isEqualTo(AdminBootstrapOutcome.ALREADY_LINKED_NOT_READY);
        assertThat(second.reasons()).containsExactly("admin_role_missing");
        verify(keycloak, never()).createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any());
        verify(keycloak, never()).assignRoleIfMissing(any(), any());
        verify(keycloak, never()).enableUser(any());
        assertThat(personRepository.count()).isEqualTo(1);
    }

    @Test
    void existingUnlinkedPersonIsAConflictWithoutAnyAccount() {
        personRepository.save(new Person(null, IdentificationType.CEDULA, USERNAME, "Otra", "Persona", "3000000001", "otra@example.test"));
        when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.empty());
        when(keycloak.findUserByEmail(EMAIL)).thenReturn(Optional.empty());
        when(keycloak.findUsersByRole(Role.ADMIN)).thenReturn(List.of());

        AdminBootstrapResult result = service.bootstrap(request());

        assertThat(result.outcome()).isEqualTo(AdminBootstrapOutcome.CONFLICT);
        assertThat(result.reasons()).containsExactly("person_exists_without_account");
        verify(keycloak, never()).createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any());
        assertThat(personRepository.findByIdentification(USERNAME).orElseThrow().getUserId()).isNull();
    }
}
