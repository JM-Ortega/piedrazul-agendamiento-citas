package co.edu.unicauca.piedrazul.backend.notifications.application;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;

import java.util.Locale;
import java.util.Objects;

/**
 * Regla única de correo utilizable, compartida por la admisión de códigos (antes de emitirlos) y
 * por el despacho (antes de llamar al proveedor), para que ambas decidan igual.
 */
public class RecipientEmailPolicy {

    private final Validator validator;

    public RecipientEmailPolicy(Validator validator) {
        this.validator = Objects.requireNonNull(validator, "El validador es obligatorio");
    }

    /** No vacío, bien formado y fuera del dominio reservado {@code .invalid}. */
    public boolean isUsable(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }

        if (!validator.validateValue(EmailValue.class, "value", email).isEmpty()) {
            return false;
        }

        return !hasReservedInvalidDomain(email);
    }

    /** {@code .invalid} (RFC 2606) nunca entrega; se usa en datos sintéticos. */
    private static boolean hasReservedInvalidDomain(String email) {
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);

        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }

        return domain.equals("invalid") || domain.endsWith(".invalid");
    }

    /** Portador de la restricción {@link Email} de Jakarta, para validar un valor suelto. */
    private static final class EmailValue {
        @Email
        private String value;
    }
}
