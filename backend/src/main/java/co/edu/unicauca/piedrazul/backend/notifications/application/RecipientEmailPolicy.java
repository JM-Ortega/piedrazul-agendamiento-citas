package co.edu.unicauca.piedrazul.backend.notifications.application;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;

import java.net.IDN;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Regla única de correo utilizable, compartida por la admisión de códigos (antes de emitirlos) y
 * por el despacho (antes de llamar al proveedor), para que ambas decidan igual. Refleja lo que
 * acepta el transporte: SES no admite SMTPUTF8, así que el sobre debe ser ASCII.
 *
 * <ul>
 *   <li>Una parte local no ASCII no tiene representación posible: no es utilizable.</li>
 *   <li>Un dominio internacionalizado se convierte a su forma {@code xn--} solo si la conversión
 *       conserva el dominio escrito. Las que lo cambiarían se rechazan: {@code ß} y {@code ς} se
 *       convertirían en {@code ss} y {@code σ}, y los caracteres de compatibilidad (ancho completo,
 *       punto ideográfico) o invisibles desaparecerían, dando otro dominio.</li>
 * </ul>
 *
 * El valor guardado no cambia: la forma ASCII solo se usa para el envío.
 */
public class RecipientEmailPolicy {

    private static final int MAX_LOCAL_PART = 64;
    private static final int MAX_ADDRESS = 254;

    private final Validator validator;

    public RecipientEmailPolicy(Validator validator) {
        this.validator = Objects.requireNonNull(validator, "El validador es obligatorio");
    }

    /** No vacío, bien formado, representable en ASCII y fuera del dominio reservado {@code .invalid}. */
    public boolean isUsable(String email) {
        return deliverableAddress(email).isPresent();
    }

    /** Dirección ASCII con la que se envía, o vacío si el correo no es utilizable. */
    public Optional<String> deliverableAddress(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }

        if (!validator.validateValue(EmailValue.class, "value", email).isEmpty()) {
            return Optional.empty();
        }

        int at = email.lastIndexOf('@');
        String localPart = email.substring(0, at);

        if (localPart.length() > MAX_LOCAL_PART || !isPrintableAscii(localPart)) {
            return Optional.empty();
        }

        return asciiDomain(email.substring(at + 1))
                .filter(domain -> !hasReservedInvalidDomain(domain))
                .map(domain -> localPart + "@" + domain)
                .filter(address -> address.length() <= MAX_ADDRESS);
    }

    private static Optional<String> asciiDomain(String domain) {
        if (isPrintableAscii(domain)) {
            return Optional.of(domain);
        }

        try {
            String ascii = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES);
            String expected = Normalizer.normalize(domain.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);

            return IDN.toUnicode(ascii, IDN.USE_STD3_ASCII_RULES).equals(expected)
                    ? Optional.of(ascii)
                    : Optional.empty();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** {@code .invalid} (RFC 2606) nunca entrega; se usa en datos sintéticos. */
    private static boolean hasReservedInvalidDomain(String asciiDomain) {
        String domain = asciiDomain.toLowerCase(Locale.ROOT);

        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }

        return domain.equals("invalid") || domain.endsWith(".invalid");
    }

    private static boolean isPrintableAscii(String value) {
        return value.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
    }

    /** Portador de la restricción {@link Email} de Jakarta, para validar un valor suelto. */
    private static final class EmailValue {
        @Email
        private String value;
    }
}
