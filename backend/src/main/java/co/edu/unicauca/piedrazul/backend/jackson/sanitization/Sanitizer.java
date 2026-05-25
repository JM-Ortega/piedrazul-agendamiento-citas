package co.edu.unicauca.piedrazul.backend.jackson.sanitization;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

public final class Sanitizer {

    private Sanitizer() {
    }

    public static String clean(String input) {
        if (input == null) {
            return null;
        }
        return Jsoup.clean(input, Safelist.basic());
    }
}
