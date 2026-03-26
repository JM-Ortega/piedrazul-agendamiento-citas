package co.edu.unicauca.piedrazul.backend.user.domain;

import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyActiveException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyInactiveException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    @Test
    void constructorShouldCreateUserWhenUsernameAndRoleAreValid() {
        User user = new User("juan123", Role.ADMIN);

        assertThat(user.getId()).isNull();
        assertThat(user.getUsername()).isEqualTo("juan123");
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void constructorShouldThrowExceptionWhenUsernameIsNull() {
        assertThatThrownBy(() -> new User(null, Role.ADMIN))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Username cannot be blank");
    }

    @Test
    void constructorShouldThrowExceptionWhenUsernameIsBlank() {
        assertThatThrownBy(() -> new User("   ", Role.ADMIN))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Username cannot be blank");
    }

    @Test
    void constructorShouldThrowExceptionWhenRoleIsNull() {
        assertThatThrownBy(() -> new User("juan123", null))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("Role cannot be null");
    }

    @Test
    void activateShouldThrowExceptionWhenUserIsAlreadyActive() {
        User user = new User("juan123", Role.ADMIN);

        assertThatThrownBy(user::activate)
                .isInstanceOf(UserAlreadyActiveException.class);
    }

    @Test
    void deactivateShouldChangeAccountStatusToInactiveWhenUserIsActive() {
        User user = new User("juan123", Role.ADMIN);

        user.deactivate();

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.INACTIVE);
    }

    @Test
    void deactivateShouldThrowExceptionWhenUserIsAlreadyInactive() {
        User user = new User("juan123", Role.ADMIN);
        user.deactivate();

        assertThatThrownBy(user::deactivate)
                .isInstanceOf(UserAlreadyInactiveException.class);
    }

    @Test
    void activateShouldChangeAccountStatusToActiveWhenUserIsInactive() {
        User user = new User("juan123", Role.ADMIN);
        user.deactivate();

        user.activate();

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }
}