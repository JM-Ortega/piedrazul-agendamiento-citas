package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reglas de la contraseña temporal del primer administrador. Solo aplican a esta entrada
 * de bootstrap: no son la política de contraseñas del realm ni dicen nada de la fuerza de
 * la contraseña definitiva, que la persona elige en el primer login ({@code UPDATE_PASSWORD}).
 *
 * <p>La comparación con valores conocidos del repositorio y con credenciales técnicas de
 * producción la hace además el despliegue (infra/ansible/roles/app/tasks/identity_seed_inputs.yml),
 * que sí tiene esos valores.
 */
final class AdminTemporaryPasswordPolicy {

    static final int MIN_LENGTH = 16;

    // Contraseñas que el repositorio publica o publicó (datos de demostración y ejemplos).
    private static final Set<String> REFERENCE_VALUES = Set.of(
            "admin1234", "admin123!", "doctor123!", "scheduler123!", "auditor123!", "patient123!");

    private AdminTemporaryPasswordPolicy() {
    }

    /** Códigos de las reglas incumplidas, nunca el valor. */
    static List<String> violations(String password, String identification, String email) {
        List<String> violations = new ArrayList<>();

        if (password.codePointCount(0, password.length()) < MIN_LENGTH) {
            violations.add("password_too_short");
        }

        if (!password.equals(password.strip())) {
            violations.add("password_edge_whitespace");
        }

        if (password.codePoints().anyMatch(AdminTemporaryPasswordPolicy::isLineBreakOrControl)) {
            violations.add("password_control_characters");
        }

        String lowered = password.toLowerCase(Locale.ROOT);

        if (identification != null && !identification.isBlank()
                && lowered.contains(identification.toLowerCase(Locale.ROOT))) {
            violations.add("password_contains_identification");
        }

        if (email != null && !email.isBlank()) {
            String loweredEmail = email.toLowerCase(Locale.ROOT);
            int at = loweredEmail.indexOf('@');
            String localPart = at > 0 ? loweredEmail.substring(0, at) : loweredEmail;
            if (lowered.contains(loweredEmail) || (localPart.length() >= 4 && lowered.contains(localPart))) {
                violations.add("password_contains_email");
            }
        }

        if (REFERENCE_VALUES.contains(lowered)) {
            violations.add("password_reference_value");
        }

        return violations;
    }

    private static boolean isLineBreakOrControl(int codePoint) {
        // isISOControl ya cubre \t \r \n y U+0085
        return Character.isISOControl(codePoint)
                || codePoint == 0x2028 // separador de línea
                || codePoint == 0x2029; // separador de párrafo
    }
}
