package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Valida las entradas del bootstrap sin ninguna operación de identidad. Las reglas de
 * negocio (documento según su tipo, nombres, correo, celular) son las mismas anotaciones
 * de {@link CreateSystemUserRequest} que valida la creación de cuentas: aquí solo se
 * evalúan, no se duplican. Encima se aplican la presencia de cada propiedad y las reglas
 * de la contraseña temporal ({@link AdminTemporaryPasswordPolicy}).
 *
 * <p>Devuelve nombres de propiedad ({@code IDENTITY_SEED_ADMIN_*}) y códigos, nunca valores.
 */
@Component
public class AdminBootstrapInputValidator {

    static final String USERNAME = "IDENTITY_SEED_ADMIN_USERNAME";
    static final String IDENTIFICATION_TYPE = "IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE";
    static final String FIRST_NAME = "IDENTITY_SEED_ADMIN_FIRST_NAME";
    static final String LAST_NAME = "IDENTITY_SEED_ADMIN_LAST_NAME";
    static final String EMAIL = "IDENTITY_SEED_ADMIN_EMAIL";
    static final String PHONE = "IDENTITY_SEED_ADMIN_PHONE";
    static final String PASSWORD = "IDENTITY_SEED_ADMIN_PASSWORD";

    private static final Map<String, String> PROPERTY_BY_FIELD = Map.of(
            "identification", USERNAME,
            "identificationType", IDENTIFICATION_TYPE,
            "firstName", FIRST_NAME,
            "lastName", LAST_NAME,
            "email", EMAIL,
            "phone", PHONE,
            "password", PASSWORD);

    private final Validator validator;

    public AdminBootstrapInputValidator(Validator validator) {
        this.validator = validator;
    }

    public List<String> invalidInputs(AdminBootstrapRequest request) {
        Set<String> invalid = new LinkedHashSet<>();

        requireText(request.username(), USERNAME, invalid);
        requireText(request.identificationType(), IDENTIFICATION_TYPE, invalid);
        requireText(request.firstName(), FIRST_NAME, invalid);
        requireText(request.lastName(), LAST_NAME, invalid);
        requireText(request.email(), EMAIL, invalid);
        requireText(request.phone(), PHONE, invalid);
        if (request.temporaryPassword() == null || request.temporaryPassword().isEmpty()) {
            invalid.add(PASSWORD);
        }

        IdentificationType type = parseType(request.identificationType());
        if (type == null) {
            invalid.add(IDENTIFICATION_TYPE);
        }

        if (!invalid.isEmpty()) {
            return new ArrayList<>(invalid);
        }

        CreateSystemUserRequest asAccountRequest = new CreateSystemUserRequest(
                request.username(),
                type,
                request.firstName(),
                request.lastName(),
                request.email(),
                request.phone(),
                request.temporaryPassword());

        for (ConstraintViolation<CreateSystemUserRequest> violation : validator.validate(asAccountRequest)) {
            invalid.add(PROPERTY_BY_FIELD.getOrDefault(leafField(violation.getPropertyPath()), "IDENTITY_SEED_ADMIN"));
        }

        AdminTemporaryPasswordPolicy.violations(request.temporaryPassword(), request.username(), request.email())
                .forEach(code -> invalid.add(PASSWORD + ":" + code));

        return new ArrayList<>(invalid);
    }

    /** Vacío, o con espacios en los extremos (un .env mal formado): se rechaza, no se recorta. */
    private static void requireText(String value, String property, Set<String> invalid) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            invalid.add(property);
        }
    }

    private static IdentificationType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        return Arrays.stream(IdentificationType.values())
                .filter(type -> type.name().equals(raw))
                .findFirst()
                .orElse(null);
    }

    private static String leafField(Path path) {
        String leaf = "";
        Iterator<Path.Node> nodes = path.iterator();
        while (nodes.hasNext()) {
            String name = nodes.next().getName();
            if (name != null) {
                leaf = name;
            }
        }
        return leaf;
    }
}
