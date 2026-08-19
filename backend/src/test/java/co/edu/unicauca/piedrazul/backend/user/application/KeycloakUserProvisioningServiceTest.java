package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contrato de provisioning de cuentas: las tres operaciones tienen semánticas
 * distintas y deliberadas.
 */
@ExtendWith(MockitoExtension.class)
class KeycloakUserProvisioningServiceTest {

    private static final String USERNAME = "1061234567";
    private static final String PASSWORD = "Secreta123";

    @Mock
    private KeycloakUserClient keycloakClient;

    @InjectMocks
    private KeycloakUserProvisioningService service;

    private final UUID existingId = UUID.randomUUID();
    private final UUID createdId = UUID.randomUUID();

    private CreateSystemUserRequest request() {
        return new CreateSystemUserRequest(
                USERNAME, IdentificationType.CEDULA, "Ana", "Ruiz",
                "ana@example.com", "3001234567", PASSWORD);
    }

    private UserRepresentation representation(UUID id) {
        UserRepresentation user = new UserRepresentation();
        user.setId(id.toString());
        user.setUsername(USERNAME);
        user.setFirstName("Ana");
        user.setLastName("Ruiz");
        user.setEmail("ana@example.com");
        return user;
    }

    private void givenCreationSucceeds() {
        when(keycloakClient.createUser(eq(USERNAME), anyString(), anyString(), anyString(), eq(PASSWORD)))
                .thenReturn(representation(createdId));
    }

    @Test
    void createAccountShouldCreateAndAssignRequestedRoles() {
        givenCreationSucceeds();

        UserSummary result = service.createAccount(request(), List.of(Role.PATIENT));

        assertEquals(createdId, result.id());
        verify(keycloakClient).assignRoleIfMissing(createdId, Role.PATIENT);
    }

    @Test
    void createAccountShouldNotAdoptExistingAccountOnConflict() {
        when(keycloakClient.createUser(eq(USERNAME), anyString(), anyString(), anyString(), eq(PASSWORD)))
                .thenThrow(new UserAlreadyExistsException());

        assertThrows(UserAlreadyExistsException.class,
                () -> service.createAccount(request(), List.of(Role.PATIENT)));

        // Ni relee la cuenta ajena ni le asigna roles.
        verify(keycloakClient, never()).findUserByUsername(anyString());
        verify(keycloakClient, never()).assignRoleIfMissing(any(), any());
    }

    @Test
    void getOrCreateUserShouldReuseExistingAccountAndAssignRoles() {
        when(keycloakClient.findUserByUsername(USERNAME))
                .thenReturn(Optional.of(representation(existingId)));

        UserSummary result = service.getOrCreateUser(request(), List.of(Role.DOCTOR, Role.SCHEDULER));

        assertEquals(existingId, result.id());
        verify(keycloakClient, never()).createUser(any(), any(), any(), any(), any());
        verify(keycloakClient).assignRoleIfMissing(existingId, Role.DOCTOR);
        verify(keycloakClient).assignRoleIfMissing(existingId, Role.SCHEDULER);
    }

    @Test
    void getOrCreateUserShouldRereadWhenAnotherRequestWinsTheRace() {
        // Carrera: no existía al buscar, pero sí al crear.
        when(keycloakClient.findUserByUsername(USERNAME))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(representation(existingId)));
        when(keycloakClient.createUser(eq(USERNAME), anyString(), anyString(), anyString(), eq(PASSWORD)))
                .thenThrow(new UserAlreadyExistsException());

        UserSummary result = service.getOrCreateUser(request(), List.of(Role.PATIENT));

        assertEquals(existingId, result.id(), "debe reutilizar la cuenta que ganó la carrera");
        verify(keycloakClient).assignRoleIfMissing(existingId, Role.PATIENT);
    }

    @Test
    void getOrCreateUserShouldRethrowWhenConflictingAccountCannotBeFound() {
        when(keycloakClient.findUserByUsername(USERNAME)).thenReturn(Optional.empty());
        when(keycloakClient.createUser(eq(USERNAME), anyString(), anyString(), anyString(), eq(PASSWORD)))
                .thenThrow(new UserAlreadyExistsException());

        assertThrows(UserAlreadyExistsException.class,
                () -> service.getOrCreateUser(request(), List.of(Role.PATIENT)));
    }

    @Test
    void ensureAccountShouldCreateWhenMissingAndGuaranteeThePassword() {
        when(keycloakClient.findUserByUsername(USERNAME)).thenReturn(Optional.empty());
        givenCreationSucceeds();

        UserSummary result = service.ensureAccount(request(), List.of(Role.PATIENT));

        assertEquals(createdId, result.id());
        verify(keycloakClient).resetPassword(createdId, PASSWORD);
        verify(keycloakClient).assignRoleIfMissing(createdId, Role.PATIENT);
    }

    @Test
    void ensureAccountShouldReuseExistingAccountAndResetItsPassword() {
        when(keycloakClient.findUserByUsername(USERNAME))
                .thenReturn(Optional.of(representation(existingId)));

        UserSummary result = service.ensureAccount(request(), List.of(Role.PATIENT));

        assertEquals(existingId, result.id());
        verify(keycloakClient, never()).createUser(any(), any(), any(), any(), any());
        // La contraseña vigente pasa a ser la de este intento verificado.
        verify(keycloakClient).resetPassword(existingId, PASSWORD);
    }

    @Test
    void ensureAccountShouldApplyOnlyTheRolesGivenByTheCaller() {
        when(keycloakClient.findUserByUsername(USERNAME))
                .thenReturn(Optional.of(representation(existingId)));

        service.ensureAccount(request(), List.of());

        verify(keycloakClient).resetPassword(existingId, PASSWORD);
        verify(keycloakClient, never()).assignRoleIfMissing(any(), any());
    }
}
