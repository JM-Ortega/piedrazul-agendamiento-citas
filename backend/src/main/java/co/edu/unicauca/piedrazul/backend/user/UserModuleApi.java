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

    /**
     * Sincroniza con el proveedor de identidad los datos de la cuenta que se
     * derivan de la persona: nombre de usuario (su identificación), nombres y
     * correo. Aplica todo o lanza excepción sin dejar la cuenta a medias.
     *
     * @throws co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException
     * si otra cuenta ya usa ese nombre de usuario o correo
     * @throws co.edu.unicauca.piedrazul.backend.user.exception.IdentityProviderException
     * si la cuenta no existe o el proveedor no aplicó el cambio
     */
    void updateUserIdentity(UUID userId, String username, String firstName, String lastName, String email);
}