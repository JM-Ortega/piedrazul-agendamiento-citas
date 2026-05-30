package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.DoctorUserProvisioningResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class UserServiceTest {

    private TestKeycloakUserService keycloakUserService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        keycloakUserService = new TestKeycloakUserService();
        userService = new UserService(keycloakUserService);
    }

    @Test
    void createUserShouldDelegateToKeycloakService() {
        UUID createdUserId = UUID.randomUUID();
        CreateSystemUserRequest request = new CreateSystemUserRequest(
                "doctor1",
                "Ana",
                "Lopez",
                "ana@test.com",
                "secret123",
                List.of(Role.DOCTOR)
        );

        keycloakUserService.createdUserId = createdUserId;

        UUID result = userService.createUser(request);

        assertThat(result).isEqualTo(createdUserId);
        assertThat(keycloakUserService.lastRequest).isNotNull();
        assertThat(keycloakUserService.lastRequest.roles()).containsExactly(Role.DOCTOR);
    }

    @Test
    void createUserShouldNormalizeDuplicateRoles() {
        UUID createdUserId = UUID.randomUUID();
        CreateSystemUserRequest request = new CreateSystemUserRequest(
                "doctor2",
                "Ana",
                "Lopez",
                "ana2@test.com",
                "secret123",
                List.of(Role.SCHEDULER, Role.DOCTOR, Role.DOCTOR)
        );

        keycloakUserService.createdUserId = createdUserId;

        UUID result = userService.createUser(request);

        assertThat(result).isEqualTo(createdUserId);
        assertThat(keycloakUserService.lastRequest.roles()).containsExactly(Role.SCHEDULER, Role.DOCTOR);
    }

    @Test
    void provisionDoctorUserShouldReportSchedulerRoleAddedForExistingUser() {
        UUID existingUserId = UUID.randomUUID();
        UUID createdUserId = UUID.randomUUID();
        CreateSystemUserRequest request = new CreateSystemUserRequest(
                "doctor3",
                "Ana",
                "Lopez",
                "ana3@test.com",
                "secret123",
                List.of(Role.DOCTOR, Role.SCHEDULER)
        );

        keycloakUserService.createdUserId = createdUserId;
        keycloakUserService.existingUserId = Optional.of(existingUserId);
        keycloakUserService.roles = List.of(Role.DOCTOR.name());

        DoctorUserProvisioningResult result = userService.provisionDoctorUser(request);

        assertThat(result.userId()).isEqualTo(createdUserId);
        assertThat(result.createdNewUser()).isFalse();
        assertThat(result.schedulerRoleAdded()).isTrue();
    }

    private static final class TestKeycloakUserService extends KeycloakUserService {

        private Optional<UUID> existingUserId = Optional.empty();
        private List<String> roles = List.of();
        private UUID createdUserId = UUID.randomUUID();
        private CreateSystemUserRequest lastRequest;

        private TestKeycloakUserService() {
            super(null);
        }

        @Override
        public Optional<UUID> findUserIdByUsername(String username) {
            return existingUserId;
        }

        @Override
        public List<String> getUserRoles(UUID userId) {
            return roles;
        }

        @Override
        public UUID getOrCreateUser(CreateSystemUserRequest request) {
            this.lastRequest = request;
            return createdUserId;
        }
    }
}