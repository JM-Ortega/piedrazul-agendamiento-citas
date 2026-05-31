package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserModuleApi {

    UUID getOrCreatePatientUser(String username, String firstName, String lastName,
                                String email, String password);

    UUID getOrCreateDoctorUser(String username, String firstName, String lastName,
                               String email, String password);

    UUID getOrCreateSchedulerUser(String username, String firstName, String lastName,
                                  String email, String password);

    UUID getOrCreateAdminUser(String username, String firstName, String lastName,
                              String email, String password);

    Optional<UUID> findUserIdByUsername(String username);

    List<UserSummary> findSchedulers();

    List<UserSummary> findDoctors();

    boolean hasSchedulerRole(UUID userId);

    boolean hasDoctrRole(UUID userId);

    List<String> getUserRoles(UUID userId);

    void ensurePatientRole(UUID userId);

    void ensureSchedulerRole(UUID userId);

    void revokeSchedulerRole(UUID userId);

    boolean existsById(UUID id);

    void activateUser(UUID id);

    void deactivateUser(UUID id);
}