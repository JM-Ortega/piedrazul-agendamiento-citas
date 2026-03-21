package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.domain.Role;
import co.edu.unicauca.piedrazul.backend.user.domain.User;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class UserService implements UserModuleApi {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public User createUser(String username, Role role) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username cannot be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("role cannot be null");
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("a user with this username already exists");
        }

        User user = new User(username, role);
        return userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    public User activateUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        user.activate();
        return userRepository.save(user);
    }

    @Override
    public User deactivateUser(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        user.deactivate();
        return userRepository.save(user);
    }
}