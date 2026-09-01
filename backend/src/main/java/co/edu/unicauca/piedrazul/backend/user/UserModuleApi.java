package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface UserModuleApi {
    Optional<UserSummary> findUserByUsername(String username);

    List<UserSummary> getSystemUsers();

    List<UserSummary> findDoctors();

    List<String> getUserRoles (UUID userId);

    Map<UUID, List<String>> getUserRolesByIds(Collection<UUID> userIds);

    void ensureSchedulerRole(UUID userId);

    void revokeSchedulerRole(UUID userId);

    void deleteUser(UUID id);

    boolean existsById(UUID id);

    void ensureDoctorRole(UUID id);

    void revokeDoctorRole(UUID id);

    void ensurePatientRole(UUID userId);
}