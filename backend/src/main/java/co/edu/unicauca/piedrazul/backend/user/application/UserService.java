package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.domain.Role;
import co.edu.unicauca.piedrazul.backend.user.domain.User;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserNotFoundException;
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
    public UUID createPatientUser(String username) {
        return createUserWithRole(username, Role.PATIENT).getId();
    }

    @Override
    public UUID createDoctorUser(String username) {
        return createUserWithRole(username, Role.DOCTOR).getId();
    }

    @Override
    public UUID createSchedulerUser(String username) {
        return createUserWithRole(username, Role.SCHEDULER).getId();
    }

    @Override
    public UUID createAdminUser(String username) {
        return createUserWithRole(username, Role.ADMIN).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(UUID id) {
        validateId(id);
        return userRepository.existsById(id);
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(UUID id) {
        validateId(id);
        return userRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        validateUsername(username);
        return userRepository.findByUsername(username);
    }

    public void activateUser(UUID id) {
        User user = findUserByIdOrThrow(id);
        user.activate();
        userRepository.save(user);

    }

    public void deactivateUser(UUID id) {
        User user = findUserByIdOrThrow(id);
        user.deactivate();
        userRepository.save(user);

    }

    private User createUserWithRole(String username, Role role) {
        ensureUsernameDoesNotExist(username);

        User user = new User(username, role);
        return userRepository.save(user);
    }

    private User findUserByIdOrThrow(UUID id) {
        validateId(id);
        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    private void ensureUsernameDoesNotExist(String username) {
        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException(username);
        }
    }

    private void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new InvalidUserDataException("Username cannot be blank");
        }
    }

    private void validateId(UUID id) {
        if (id == null) {
            throw new InvalidUserDataException("Id cannot be null");
        }
    }
}