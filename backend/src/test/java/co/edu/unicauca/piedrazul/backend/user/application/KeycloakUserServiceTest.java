package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KeycloakUserServiceTest {

    @Mock
    private KeycloakUserClient keycloakClient;

    @InjectMocks
    private KeycloakUserService keycloakUserService;

    @Test
    void getOrCreateUserShouldCreateUserWithPrimaryRoleAndAssignTheRest() {
        String username = "doctor1";
        UUID createdUserId = UUID.randomUUID();
        CreateSystemUserRequest request = new CreateSystemUserRequest(
                username,
                "Ana",
                "Lopez",
                "ana@test.com",
                "secret",
                List.of(Role.DOCTOR, Role.SCHEDULER)
        );

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.empty());
        when(keycloakClient.createUser(username, "Ana", "Lopez", "ana@test.com", "secret", Role.DOCTOR))
                .thenReturn(createdUserId);

        UUID result = keycloakUserService.getOrCreateUser(request);

        assertEquals(createdUserId, result);
        verify(keycloakClient).createUser(username, "Ana", "Lopez", "ana@test.com", "secret", Role.DOCTOR);
        verify(keycloakClient).assignRoleIfMissing(createdUserId, Role.SCHEDULER);
    }

    @Test
    void getOrCreateUserShouldAssignMissingRolesWhenUserAlreadyExists() {
        String username = "juanperez";
        UUID existingUserId = UUID.randomUUID();
        CreateSystemUserRequest request = new CreateSystemUserRequest(
                username,
                "Juan",
                "Perez",
                "juan@test.com",
                "secret",
                List.of(Role.PATIENT)
        );

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.of(existingUserId));

        UUID result = keycloakUserService.getOrCreateUser(request);

        assertEquals(existingUserId, result);
        verify(keycloakClient).assignRoleIfMissing(existingUserId, Role.PATIENT);
        verify(keycloakClient, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void getOrCreateUserShouldRejectUnsupportedRoleCombination() {
        CreateSystemUserRequest request = new CreateSystemUserRequest(
                "mixed1",
                "Juan",
                "Perez",
                "juan@test.com",
                "secret",
                List.of(Role.ADMIN, Role.DOCTOR)
        );

        assertThrows(InvalidUserDataException.class, () -> keycloakUserService.getOrCreateUser(request));
    }

    @Test
    void getOrCreateUserShouldRejectEmptyRoleList() {
        CreateSystemUserRequest request = new CreateSystemUserRequest(
                "mixed2",
                "Juan",
                "Perez",
                "juan@test.com",
                "secret",
                List.of()
        );

        assertThrows(InvalidUserDataException.class, () -> keycloakUserService.getOrCreateUser(request));
    }

    @Test
    void existsByIdShouldDelegateToKeycloakClient() {
        UUID userId = UUID.randomUUID();
        when(keycloakClient.existsUser(userId)).thenReturn(true);

        boolean result = keycloakUserService.existsById(userId);

        assertTrue(result);
        verify(keycloakClient).existsUser(userId);
    }

    @Test
    void activateUserShouldDelegateToKeycloakClient() {
        UUID userId = UUID.randomUUID();

        keycloakUserService.activateUser(userId);

        verify(keycloakClient).activateUser(userId);
    }

    @Test
    void deactivateUserShouldDelegateToKeycloakClient() {
        UUID userId = UUID.randomUUID();

        keycloakUserService.deactivateUser(userId);

        verify(keycloakClient).deactivateUser(userId);
    }

    @Test
    void deleteUserShouldDelegateToKeycloakClient() {
        UUID userId = UUID.randomUUID();

        keycloakUserService.deleteUser(userId);

        verify(keycloakClient).deleteUser(userId);
    }

    @Test
    void findUserIdByUsernameShouldDelegateToKeycloakClient() {
        String username = "juanperez";
        UUID userId = UUID.randomUUID();
        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.of(userId));

        Optional<UUID> result = keycloakUserService.findUserIdByUsername(username);

        assertTrue(result.isPresent());
        assertEquals(userId, result.get());
        verify(keycloakClient).findUserIdByUsername(username);
    }
}