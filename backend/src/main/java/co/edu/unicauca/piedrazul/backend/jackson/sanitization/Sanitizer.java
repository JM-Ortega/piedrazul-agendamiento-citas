package co.edu.unicauca.piedrazul.backend.jackson.sanitization;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;

/**
 * Quita el HTML peligroso de un texto sin escaparlo: conserva las etiquetas básicas de
 * {@link Safelist#basic()} y deja el resto del texto tal como se escribió ({@code &}, {@code <}
 * y {@code >} no se convierten en entidades).
 */
public final class Sanitizer {

    private static final Safelist SAFELIST = Safelist.basic();

    // Cada vuelta deshace un nivel de anidamiento; ningún texto razonable necesita tantos.
    private static final int MAX_PASSES = 10;

    private Sanitizer() {
    }

    public static String clean(String input) {
        if (input == null) {
            return null;
        }

        String current = input;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            // Sin '<' no puede haber etiquetas. Además evita decodificar entidades que la
            // persona escribió: "&lt;script&gt;" debe quedarse como texto.
            if (current.indexOf('<') < 0) {
                return current;
            }

            // Jsoup escapa el texto al limpiar; se deshace ese escape para guardar lo escrito.
            // Si al deshacerlo aparece una etiqueta nueva (ataques anidados como
            // "<<script>script>"), la siguiente vuelta la limpia.
            String cleaned = Parser.unescapeEntities(Jsoup.clean(current, SAFELIST), false);
            if (cleaned.equals(current)) {
                return current;
            }
            current = cleaned;
        }

        // No se estabilizó: se devuelve la versión escapada, que siempre es segura.
        return Jsoup.clean(current, SAFELIST);
    }
}
