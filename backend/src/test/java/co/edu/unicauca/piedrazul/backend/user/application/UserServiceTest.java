package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.user.domain.AccountStatus;
import co.edu.unicauca.piedrazul.backend.user.domain.Role;
import co.edu.unicauca.piedrazul.backend.user.domain.User;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyActiveException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyInactiveException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserNotFoundException;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void createPatientUserShouldCreateAndReturnUserIdWhenUsernameIsValidAndDoesNotExist() {
        UUID userId = UUID.randomUUID();
        User savedUser = mock(User.class);

        when(userRepository.existsByUsername("patient1")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(savedUser.getId()).thenReturn(userId);

        UUID result = userService.createPatientUser("patient1");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User userToSave = userCaptor.getValue();
        assertThat(userToSave.getUsername()).isEqualTo("patient1");
        assertThat(userToSave.getRole()).isEqualTo(Role.PATIENT);
        assertThat(userToSave.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(result).isEqualTo(userId);
    }

    @Test
    void createDoctorUserShouldCreateAndReturnUserIdWhenUsernameIsValidAndDoesNotExist() {
        UUID userId = UUID.randomUUID();
        User savedUser = mock(User.class);

        when(userRepository.existsByUsername("doctor1")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(savedUser.getId()).thenReturn(userId);

        UUID result = userService.createDoctorUser("doctor1");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User userToSave = userCaptor.getValue();
        assertThat(userToSave.getUsername()).isEqualTo("doctor1");
        assertThat(userToSave.getRole()).isEqualTo(Role.DOCTOR);
        assertThat(userToSave.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(result).isEqualTo(userId);
    }

    @Test
    void createSchedulerUserShouldCreateAndReturnUserIdWhenUsernameIsValidAndDoesNotExist() {
        UUID userId = UUID.randomUUID();
        User savedUser = mock(User.class);

        when(userRepository.existsByUsername("scheduler1")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(savedUser.getId()).thenReturn(userId);

        UUID result = userService.createSchedulerUser("scheduler1");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User userToSave = userCaptor.getValue();
        assertThat(userToSave.getUsername()).isEqualTo("scheduler1");
        assertThat(userToSave.getRole()).isEqualTo(Role.SCHEDULER);
        assertThat(userToSave.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(result).isEqualTo(userId);
    }

    @Test
    void createAdminUserShouldCreateAndReturnUserIdWhenUsernameIsValidAndDoesNotExist() {
        UUID userId = UUID.randomUUID();
        User savedUser = mock(User.class);

        when(userRepository.existsByUsername("admin1")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(savedUser.getId()).thenReturn(userId);

        UUID result = userService.createAdminUser("admin1");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User userToSave = userCaptor.getValue();
        assertThat(userToSave.getUsername()).isEqualTo("admin1");
        assertThat(userToSave.getRole()).isEqualTo(Role.ADMIN);
        assertThat(userToSave.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(result).isEqualTo(userId);
    }

    @Test
    void createPatientUserShouldThrowExceptionWhenUsernameAlreadyExists() {
        when(userRepository.existsByUsername("patient1")).thenReturn(true);

        assertThatThrownBy(() -> userService.createPatientUser("patient1"))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void createDoctorUserShouldThrowExceptionWhenUsernameAlreadyExists() {
        when(userRepository.existsByUsername("doctor1")).thenReturn(true);

        assertThatThrownBy(() -> userService.createDoctorUser("doctor1"))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void createSchedulerUserShouldThrowExceptionWhenUsernameAlreadyExists() {
        when(userRepository.existsByUsername("scheduler1")).thenReturn(true);

        assertThatThrownBy(() -> userService.createSchedulerUser("scheduler1"))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void createAdminUserShouldThrowExceptionWhenUsernameAlreadyExists() {
        when(userRepository.existsByUsername("admin1")).thenReturn(true);

        assertThatThrownBy(() -> userService.createAdminUser("admin1"))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void createPatientUserShouldThrowExceptionWhenUsernameIsNull() {
        when(userRepository.existsByUsername(null)).thenReturn(false);

        assertThatThrownBy(() -> userService.createPatientUser(null))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Username cannot be blank");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void createDoctorUserShouldThrowExceptionWhenUsernameIsBlank() {
        when(userRepository.existsByUsername("   ")).thenReturn(false);

        assertThatThrownBy(() -> userService.createDoctorUser("   "))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Username cannot be blank");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void existsByIdShouldReturnTrueWhenUserExists() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(true);

        boolean result = userService.existsById(userId);

        assertThat(result).isTrue();
        verify(userRepository).existsById(userId);
    }

    @Test
    void existsByIdShouldReturnFalseWhenUserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.existsById(userId)).thenReturn(false);

        boolean result = userService.existsById(userId);

        assertThat(result).isFalse();
        verify(userRepository).existsById(userId);
    }

    @Test
    void existsByIdShouldThrowExceptionWhenIdIsNull() {
        assertThatThrownBy(() -> userService.existsById(null))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Id cannot be null");

        verify(userRepository, never()).existsById(any());
    }

    @Test
    void findByIdShouldReturnUserWhenIdExists() {
        UUID userId = UUID.randomUUID();
        User user = new User("juan123", Role.ADMIN);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Optional<User> result = userService.findById(userId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(user);
        verify(userRepository).findById(userId);
    }

    @Test
    void findByIdShouldReturnEmptyWhenIdDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        Optional<User> result = userService.findById(userId);

        assertThat(result).isEmpty();
        verify(userRepository).findById(userId);
    }

    @Test
    void findByIdShouldThrowExceptionWhenIdIsNull() {
        assertThatThrownBy(() -> userService.findById(null))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Id cannot be null");

        verify(userRepository, never()).findById(any());
    }

    @Test
    void findByUsernameShouldReturnUserWhenUsernameExists() {
        User user = new User("juan123", Role.ADMIN);
        when(userRepository.findByUsername("juan123")).thenReturn(Optional.of(user));

        Optional<User> result = userService.findByUsername("juan123");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(user);
        verify(userRepository).findByUsername("juan123");
    }

    @Test
    void findByUsernameShouldReturnEmptyWhenUsernameDoesNotExist() {
        when(userRepository.findByUsername("juan123")).thenReturn(Optional.empty());

        Optional<User> result = userService.findByUsername("juan123");

        assertThat(result).isEmpty();
        verify(userRepository).findByUsername("juan123");
    }

    @Test
    void findByUsernameShouldThrowExceptionWhenUsernameIsNull() {
        assertThatThrownBy(() -> userService.findByUsername(null))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Username cannot be blank");

        verify(userRepository, never()).findByUsername(any());
    }

    @Test
    void findByUsernameShouldThrowExceptionWhenUsernameIsBlank() {
        assertThatThrownBy(() -> userService.findByUsername("   "))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Username cannot be blank");

        verify(userRepository, never()).findByUsername(any());
    }

    @Test
    void activateUserShouldActivateAndSaveWhenUserExistsAndIsInactive() {
        UUID userId = UUID.randomUUID();
        User user = new User("juan123", Role.ADMIN);
        user.deactivate();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.activateUser(userId);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(userRepository).save(user);
    }

    @Test
    void activateUserShouldThrowExceptionWhenUserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.activateUser(userId))
                .isInstanceOf(UserNotFoundException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void activateUserShouldThrowExceptionWhenIdIsNull() {
        assertThatThrownBy(() -> userService.activateUser(null))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Id cannot be null");

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void activateUserShouldThrowExceptionWhenUserIsAlreadyActive() {
        UUID userId = UUID.randomUUID();
        User user = new User("juan123", Role.ADMIN);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.activateUser(userId))
                .isInstanceOf(UserAlreadyActiveException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void deactivateUserShouldDeactivateAndSaveWhenUserExistsAndIsActive() {
        UUID userId = UUID.randomUUID();
        User user = new User("juan123", Role.ADMIN);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.deactivateUser(userId);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.INACTIVE);
        verify(userRepository).save(user);
    }

    @Test
    void deactivateUserShouldThrowExceptionWhenUserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deactivateUser(userId))
                .isInstanceOf(UserNotFoundException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void deactivateUserShouldThrowExceptionWhenIdIsNull() {
        assertThatThrownBy(() -> userService.deactivateUser(null))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Id cannot be null");

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void deactivateUserShouldThrowExceptionWhenUserIsAlreadyInactive() {
        UUID userId = UUID.randomUUID();
        User user = new User("juan123", Role.ADMIN);
        user.deactivate();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.deactivateUser(userId))
                .isInstanceOf(UserAlreadyInactiveException.class);

        verify(userRepository, never()).save(any(User.class));
    }
}