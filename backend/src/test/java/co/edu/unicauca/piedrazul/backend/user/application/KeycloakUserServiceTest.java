package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KeycloakUserServiceTest {

    @Mock
    private KeycloakUserClient keycloakClient;

    @InjectMocks
    private KeycloakUserService keycloakUserService;

    @Test
    void getOrCreatePatientUser_shouldReturnExistingUserAndAssignPatientRole_whenUserAlreadyExists() {
        String username = "juanperez";
        String firstName = "Juan";
        String lastName = "Perez";
        String email = "juan@test.com";
        String password = "secret";
        UUID existingUserId = UUID.randomUUID();

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.of(existingUserId));

        UUID result = keycloakUserService.getOrCreatePatientUser(
                username, firstName, lastName, email, password
        );

        assertEquals(existingUserId, result);
        verify(keycloakClient).findUserIdByUsername(username);
        verify(keycloakClient).assignRoleIfMissing(existingUserId, Role.PATIENT);
        verify(keycloakClient, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void getOrCreatePatientUser_shouldCreateUserWithPatientRole_whenUserDoesNotExist() {
        String username = "juanperez";
        String firstName = "Juan";
        String lastName = "Perez";
        String email = "juan@test.com";
        String password = "secret";
        UUID createdUserId = UUID.randomUUID();

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.empty());
        when(keycloakClient.createUser(username, firstName, lastName, email, password, Role.PATIENT))
                .thenReturn(createdUserId);

        UUID result = keycloakUserService.getOrCreatePatientUser(
                username, firstName, lastName, email, password
        );

        assertEquals(createdUserId, result);
        verify(keycloakClient).findUserIdByUsername(username);
        verify(keycloakClient).createUser(username, firstName, lastName, email, password, Role.PATIENT);
        verify(keycloakClient, never()).assignRoleIfMissing(any(UUID.class), any(Role.class));
    }

    @Test
    void getOrCreateDoctorUser_shouldReturnExistingUserAndAssignDoctorRole_whenUserAlreadyExists() {
        String username = "doctor1";
        String firstName = "Ana";
        String lastName = "Lopez";
        String email = "ana@test.com";
        String password = "secret";
        UUID existingUserId = UUID.randomUUID();

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.of(existingUserId));

        UUID result = keycloakUserService.getOrCreateDoctorUser(
                username, firstName, lastName, email, password
        );

        assertEquals(existingUserId, result);
        verify(keycloakClient).findUserIdByUsername(username);
        verify(keycloakClient).assignRoleIfMissing(existingUserId, Role.DOCTOR);
        verify(keycloakClient, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void getOrCreateDoctorUser_shouldCreateUserWithDoctorRole_whenUserDoesNotExist() {
        String username = "doctor1";
        String firstName = "Ana";
        String lastName = "Lopez";
        String email = "ana@test.com";
        String password = "secret";
        UUID createdUserId = UUID.randomUUID();

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.empty());
        when(keycloakClient.createUser(username, firstName, lastName, email, password, Role.DOCTOR))
                .thenReturn(createdUserId);

        UUID result = keycloakUserService.getOrCreateDoctorUser(
                username, firstName, lastName, email, password
        );

        assertEquals(createdUserId, result);
        verify(keycloakClient).findUserIdByUsername(username);
        verify(keycloakClient).createUser(username, firstName, lastName, email, password, Role.DOCTOR);
        verify(keycloakClient, never()).assignRoleIfMissing(any(UUID.class), any(Role.class));
    }

    @Test
    void getOrCreateSchedulerUser_shouldReturnExistingUserAndAssignSchedulerRole_whenUserAlreadyExists() {
        String username = "scheduler1";
        String firstName = "Maria";
        String lastName = "Gomez";
        String email = "maria@test.com";
        String password = "secret";
        UUID existingUserId = UUID.randomUUID();

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.of(existingUserId));

        UUID result = keycloakUserService.getOrCreateSchedulerUser(
                username, firstName, lastName, email, password
        );

        assertEquals(existingUserId, result);
        verify(keycloakClient).findUserIdByUsername(username);
        verify(keycloakClient).assignRoleIfMissing(existingUserId, Role.SCHEDULER);
        verify(keycloakClient, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void getOrCreateSchedulerUser_shouldCreateUserWithSchedulerRole_whenUserDoesNotExist() {
        String username = "scheduler1";
        String firstName = "Maria";
        String lastName = "Gomez";
        String email = "maria@test.com";
        String password = "secret";
        UUID createdUserId = UUID.randomUUID();

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.empty());
        when(keycloakClient.createUser(username, firstName, lastName, email, password, Role.SCHEDULER))
                .thenReturn(createdUserId);

        UUID result = keycloakUserService.getOrCreateSchedulerUser(
                username, firstName, lastName, email, password
        );

        assertEquals(createdUserId, result);
        verify(keycloakClient).findUserIdByUsername(username);
        verify(keycloakClient).createUser(username, firstName, lastName, email, password, Role.SCHEDULER);
        verify(keycloakClient, never()).assignRoleIfMissing(any(UUID.class), any(Role.class));
    }

    @Test
    void getOrCreateAdminUser_shouldReturnExistingUserAndAssignAdminRole_whenUserAlreadyExists() {
        String username = "admin1";
        String firstName = "Carlos";
        String lastName = "Ruiz";
        String email = "carlos@test.com";
        String password = "secret";
        UUID existingUserId = UUID.randomUUID();

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.of(existingUserId));

        UUID result = keycloakUserService.getOrCreateAdminUser(
                username, firstName, lastName, email, password
        );

        assertEquals(existingUserId, result);
        verify(keycloakClient).findUserIdByUsername(username);
        verify(keycloakClient).assignRoleIfMissing(existingUserId, Role.ADMIN);
        verify(keycloakClient, never()).createUser(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void getOrCreateAdminUser_shouldCreateUserWithAdminRole_whenUserDoesNotExist() {
        String username = "admin1";
        String firstName = "Carlos";
        String lastName = "Ruiz";
        String email = "carlos@test.com";
        String password = "secret";
        UUID createdUserId = UUID.randomUUID();

        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.empty());
        when(keycloakClient.createUser(username, firstName, lastName, email, password, Role.ADMIN))
                .thenReturn(createdUserId);

        UUID result = keycloakUserService.getOrCreateAdminUser(
                username, firstName, lastName, email, password
        );

        assertEquals(createdUserId, result);
        verify(keycloakClient).findUserIdByUsername(username);
        verify(keycloakClient).createUser(username, firstName, lastName, email, password, Role.ADMIN);
        verify(keycloakClient, never()).assignRoleIfMissing(any(UUID.class), any(Role.class));
    }

    @Test
    void existsById_shouldDelegateToKeycloakClient() {
        UUID userId = UUID.randomUUID();
        when(keycloakClient.existsUser(userId)).thenReturn(true);

        boolean result = keycloakUserService.existsById(userId);

        assertTrue(result);
        verify(keycloakClient).existsUser(userId);
    }

    @Test
    void activateUser_shouldDelegateToKeycloakClient() {
        UUID userId = UUID.randomUUID();

        keycloakUserService.activateUser(userId);

        verify(keycloakClient).activateUser(userId);
    }

    @Test
    void deactivateUser_shouldDelegateToKeycloakClient() {
        UUID userId = UUID.randomUUID();

        keycloakUserService.deactivateUser(userId);

        verify(keycloakClient).deactivateUser(userId);
    }

    @Test
    void findUserIdByUsername_shouldDelegateToKeycloakClient() {
        String username = "juanperez";
        UUID userId = UUID.randomUUID();
        when(keycloakClient.findUserIdByUsername(username)).thenReturn(Optional.of(userId));

        Optional<UUID> result = keycloakUserService.findUserIdByUsername(username);

        assertTrue(result.isPresent());
        assertEquals(userId, result.get());
        verify(keycloakClient).findUserIdByUsername(username);
    }

    @Test
    void ensurePatientRole_shouldAssignPatientRole() {
        UUID userId = UUID.randomUUID();

        keycloakUserService.ensurePatientRole(userId);

        verify(keycloakClient).assignRoleIfMissing(userId, Role.PATIENT);
    }
}