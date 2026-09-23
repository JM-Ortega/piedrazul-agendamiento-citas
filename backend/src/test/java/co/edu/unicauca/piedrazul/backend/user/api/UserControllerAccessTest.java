package co.edu.unicauca.piedrazul.backend.user.api;

import co.edu.unicauca.piedrazul.backend.user.application.CreateAccountUseCase;
import co.edu.unicauca.piedrazul.backend.user.application.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Es seguridad por método, así que se prueba con un contexto de Spring con
 * {@code @EnableMethodSecurity}: llamar al método sobre una instancia suelta no
 * evaluaría la anotación.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = UserControllerAccessTest.Config.class)
class UserControllerAccessTest {

    private static final UUID PATIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        UserController userController(UserService userService) {
            return new UserController(mock(CreateAccountUseCase.class), userService);
        }
    }

    @Autowired
    private UserController controller;

    @Autowired
    private UserService userService;

    // El mock es un bean compartido entre tests: sin reiniciarlo, un test vería las llamadas del anterior.
    @BeforeEach
    void resetMock() {
        reset(userService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loggedInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("user", "n/a", Arrays.asList("ROLE_" + role).toArray(String[]::new)));
    }

    @Test
    void anAdminCanActivateAndDeactivateAPatientUser() {
        loggedInAs("ADMIN");

        assertThatCode(() -> controller.activatePatientUser(PATIENT_ID)).doesNotThrowAnyException();
        assertThatCode(() -> controller.deactivatePatientUser(PATIENT_ID)).doesNotThrowAnyException();

        verify(userService).activatePatientUser(PATIENT_ID);
        verify(userService).deactivatePatientUser(PATIENT_ID);
    }

    @Test
    void everyOtherRoleIsDenied() {
        for (String role : new String[]{"DOCTOR", "SCHEDULER", "PATIENT"}) {
            loggedInAs(role);

            assertThatThrownBy(() -> controller.activatePatientUser(PATIENT_ID))
                    .as("activar como " + role).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.deactivatePatientUser(PATIENT_ID))
                    .as("desactivar como " + role).isInstanceOf(AccessDeniedException.class);
        }

        verifyNoInteractions(userService);
    }
}
