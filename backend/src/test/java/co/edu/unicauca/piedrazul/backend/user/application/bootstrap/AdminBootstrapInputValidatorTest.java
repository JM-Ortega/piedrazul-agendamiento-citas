package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Con el validador real: las reglas de negocio vienen de las anotaciones de
 * CreateSystemUserRequest y las de la contraseña temporal de AdminTemporaryPasswordPolicy.
 * El resultado nombra propiedades y reglas, nunca valores.
 */
class AdminBootstrapInputValidatorTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final String PASSWORD = "Temp-Bootstrap-Value-2026!";

    private final AdminBootstrapInputValidator validator = new AdminBootstrapInputValidator(FACTORY.getValidator());

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    private static AdminBootstrapRequest valid() {
        return new AdminBootstrapRequest("1061234567", "CEDULA", "Ana María", "Prueba", "ana.prueba@example.test", "3000000000", PASSWORD);
    }

    private static AdminBootstrapRequest withPassword(String password) {
        AdminBootstrapRequest r = valid();
        return new AdminBootstrapRequest(r.username(), r.identificationType(), r.firstName(), r.lastName(), r.email(), r.phone(), password);
    }

    @Test
    void acceptsCompleteValidInput() {
        assertThat(validator.invalidInputs(valid())).isEmpty();
    }

    @Test
    void reportsEveryMissingPropertyByName() {
        List<String> invalid = validator.invalidInputs(new AdminBootstrapRequest(null, "", " ", null, "", null, ""));

        assertThat(invalid).containsExactlyInAnyOrder(
                "IDENTITY_SEED_ADMIN_USERNAME",
                "IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE",
                "IDENTITY_SEED_ADMIN_FIRST_NAME",
                "IDENTITY_SEED_ADMIN_LAST_NAME",
                "IDENTITY_SEED_ADMIN_EMAIL",
                "IDENTITY_SEED_ADMIN_PHONE",
                "IDENTITY_SEED_ADMIN_PASSWORD");
    }

    @Test
    void rejectsUnknownIdentificationType() {
        AdminBootstrapRequest r = valid();
        assertThat(validator.invalidInputs(new AdminBootstrapRequest(r.username(), "DNI", r.firstName(), r.lastName(), r.email(), r.phone(), PASSWORD)))
                .containsExactly("IDENTITY_SEED_ADMIN_IDENTIFICATION_TYPE");
    }

    @Test
    void appliesDocumentRulesOfTheIdentificationType() {
        AdminBootstrapRequest r = valid();
        // Una cédula no admite letras (regla de ValidDocument)
        assertThat(validator.invalidInputs(new AdminBootstrapRequest("12AB5678", "CEDULA", r.firstName(), r.lastName(), r.email(), r.phone(), PASSWORD)))
                .contains("IDENTITY_SEED_ADMIN_USERNAME");
        // Pero un pasaporte sí
        assertThat(validator.invalidInputs(new AdminBootstrapRequest("AB123456", "PASAPORTE", r.firstName(), r.lastName(), r.email(), r.phone(), PASSWORD)))
                .isEmpty();
    }

    @Test
    void appliesAccountRulesForEmailPhoneAndNames() {
        AdminBootstrapRequest r = valid();
        assertThat(validator.invalidInputs(new AdminBootstrapRequest(r.username(), "CEDULA", r.firstName(), r.lastName(), "no-es-correo", r.phone(), PASSWORD)))
                .containsExactly("IDENTITY_SEED_ADMIN_EMAIL");
        assertThat(validator.invalidInputs(new AdminBootstrapRequest(r.username(), "CEDULA", r.firstName(), r.lastName(), r.email(), "300123", PASSWORD)))
                .containsExactly("IDENTITY_SEED_ADMIN_PHONE");
        assertThat(validator.invalidInputs(new AdminBootstrapRequest(r.username(), "CEDULA", "Ana3", r.lastName(), r.email(), r.phone(), PASSWORD)))
                .containsExactly("IDENTITY_SEED_ADMIN_FIRST_NAME");
    }

    @Test
    void rejectsEdgeWhitespaceInsteadOfTrimming() {
        AdminBootstrapRequest r = valid();
        assertThat(validator.invalidInputs(new AdminBootstrapRequest(r.username(), "CEDULA", "Ana ", r.lastName(), r.email(), r.phone(), PASSWORD)))
                .containsExactly("IDENTITY_SEED_ADMIN_FIRST_NAME");
    }

    @Test
    void passwordShorterThanSixteenIsRejected() {
        assertThat(validator.invalidInputs(withPassword("Corta-Clave-15!")))
                .containsExactly("IDENTITY_SEED_ADMIN_PASSWORD:password_too_short");
    }

    @Test
    void sixteenCharactersIsTheMinimum() {
        assertThat(validator.invalidInputs(withPassword("Exacta-16-chars!"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {" Temp-Bootstrap-Value-2026", "Temp-Bootstrap-Value-2026 ", "Temp-Bootstrap-Value-2026\n"})
    void passwordWithEdgeWhitespaceIsRejected(String password) {
        assertThat(validator.invalidInputs(withPassword(password)))
                .contains("IDENTITY_SEED_ADMIN_PASSWORD:password_edge_whitespace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Temp-Boot\nstrap-Value-2026", "Temp-Boot\rstrap-Value-2026", "Temp-Boot\tstrap-Value-2026", "Temp-Boot strap-Value-2026"})
    void passwordWithLineBreaksOrControlCharactersIsRejected(String password) {
        assertThat(validator.invalidInputs(withPassword(password)))
                .contains("IDENTITY_SEED_ADMIN_PASSWORD:password_control_characters");
    }

    @Test
    void passwordContainingTheDocumentIsRejected() {
        assertThat(validator.invalidInputs(withPassword("Clave-1061234567-Inicial")))
                .containsExactly("IDENTITY_SEED_ADMIN_PASSWORD:password_contains_identification");
    }

    @Test
    void passwordContainingTheEmailIsRejected() {
        assertThat(validator.invalidInputs(withPassword("X-ANA.PRUEBA-Temporal-2026")))
                .containsExactly("IDENTITY_SEED_ADMIN_PASSWORD:password_contains_email");
    }

    @Test
    void interiorSpacesAndSpecialCharactersAreAllowed() {
        assertThat(validator.invalidInputs(withPassword("una frase $larga 'con' \"comillas\" #y\\barras"))).isEmpty();
    }

    @Test
    void neverEchoesValues() {
        List<String> invalid = validator.invalidInputs(withPassword("Clave-1061234567"));

        assertThat(String.join(" ", invalid)).doesNotContain("Clave", "1061234567");
    }
}
