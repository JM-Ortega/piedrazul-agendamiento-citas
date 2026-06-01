package co.edu.unicauca.piedrazul.backend.user.api.dto;

import co.edu.unicauca.piedrazul.backend.shared.auth.Role;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateUserRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void shouldBeValid_whenUsernameAndRoleAreProvided() {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("123456789");
        request.setRole(Role.PATIENT);

        Set<ConstraintViolation<CreateUserRequest>> violations = validator.validate(request);

        assertTrue(violations.isEmpty());
    }

    @Test
    void shouldFail_whenUsernameIsNull() {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername(null);
        request.setRole(Role.PATIENT);

        Set<ConstraintViolation<CreateUserRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
    }

    @Test
    void shouldFail_whenUsernameIsBlank() {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("   ");
        request.setRole(Role.PATIENT);

        Set<ConstraintViolation<CreateUserRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
    }

    @Test
    void shouldFail_whenUsernameExceedsMaxLength() {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("a".repeat(51));
        request.setRole(Role.PATIENT);

        Set<ConstraintViolation<CreateUserRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
    }

    @Test
    void shouldFail_whenRoleIsNull() {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("123456789");
        request.setRole(null);

        Set<ConstraintViolation<CreateUserRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
    }
}