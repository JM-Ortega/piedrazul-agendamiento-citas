package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import java.util.Locale;

/**
 * Datos del primer administrador de negocio (rol {@code ADMIN} del realm de la
 * aplicación), tal como llegan de {@code IDENTITY_SEED_ADMIN_*}. {@code username} es el
 * número de documento, como en el resto de cuentas.
 *
 * <p>{@code toString} no incluye ningún valor: el registro contiene la contraseña
 * temporal y datos personales.
 */
public record AdminBootstrapRequest(
        String username,
        String identificationType,
        String firstName,
        String lastName,
        String email,
        String phone,
        String temporaryPassword
) {

    /** El correo en minúsculas: Keycloak lo guarda así y Person también. */
    AdminBootstrapRequest normalized() {
        return new AdminBootstrapRequest(
                username,
                identificationType,
                firstName,
                lastName,
                email == null ? null : email.toLowerCase(Locale.ROOT),
                phone,
                temporaryPassword);
    }

    @Override
    public String toString() {
        return "AdminBootstrapRequest[redacted]";
    }
}
