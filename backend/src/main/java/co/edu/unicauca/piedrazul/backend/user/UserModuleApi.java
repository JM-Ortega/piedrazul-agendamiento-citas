package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.user.domain.Role;
import co.edu.unicauca.piedrazul.backend.user.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserModuleApi {

    User createUser(String username, Role role);

    Optional<User> findById(UUID id);

    Optional<User> findByUsername(String username);

    User activateUser(UUID id);

    User deactivateUser(UUID id);
}