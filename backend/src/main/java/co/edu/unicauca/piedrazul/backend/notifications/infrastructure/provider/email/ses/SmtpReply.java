package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.provider.email.ses;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Respuesta SMTP leída con el rigor que Angus no aplica: Angus toma el código de los tres primeros
 * caracteres, así que {@code 2500 x} o una respuesta multilínea con códigos distintos le bastan
 * para ver un 250. El texto del servidor solo se usa para extraer el id de mensaje; nunca se guarda
 * ni se escribe en el log.
 */
record SmtpReply(int code, boolean wellFormed, String text) {

    private static final Pattern LINE = Pattern.compile("(\\d{3})([ -].*)?");
    // Forma de la respuesta final de SES: "250 Ok <id>"
    private static final Pattern SES_MESSAGE_ID =
            Pattern.compile("^250 (?:2\\.\\d{1,3}\\.\\d{1,3} )?Ok ([0-9A-Za-z-]{20,100})\\s*$");

    /** @param terminated si la respuesta terminó en CRLF; Angus devuelve igual una línea cortada */
    static SmtpReply parse(int angusCode, String raw, boolean terminated) {
        return new SmtpReply(angusCode, terminated && isWellFormed(angusCode, raw), raw);
    }

    private static boolean isWellFormed(int code, String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }

        List<String> lines = raw.lines().toList();

        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = LINE.matcher(lines.get(index));
            boolean last = index == lines.size() - 1;

            if (!matcher.matches()
                    || Integer.parseInt(matcher.group(1)) != code
                    || (matcher.group(2) != null && matcher.group(2).startsWith("-")) == last) {
                return false;
            }
        }

        return true;
    }

    boolean isPermanentRejection() {
        return wellFormed && code >= 500 && code <= 599;
    }

    boolean isTransientRejection() {
        return wellFormed && code >= 400 && code <= 499;
    }

    boolean isRejection() {
        return isPermanentRejection() || isTransientRejection();
    }

    boolean isExactly(int expected) {
        return wellFormed && code == expected;
    }

    /** Id de mensaje que SES devuelve en la respuesta final, si tiene exactamente esa forma. */
    Optional<String> sesMessageId() {
        Matcher matcher = SES_MESSAGE_ID.matcher(text == null ? "" : text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
