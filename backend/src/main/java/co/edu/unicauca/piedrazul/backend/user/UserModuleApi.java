package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserModuleApi {
    Optional<UserSummary> findUserByUsername(String username);

    List<UserSummary> findSchedulers();

    List<UserSummary> findDoctors();

    List<String> getUserRoles(UUID userId);

    void ensureSchedulerRole(UUID userId);

    void revokeSchedulerRole(UUID userId);

    void deleteUser(UUID id);

    boolean existsById(UUID id);

    void activateUser(UUID id);

    void deactivateUser(UUID id);
}