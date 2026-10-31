package co.edu.unicauca.piedrazul.backend.jackson.normalization;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Normaliza un nombre: sin espacios sobrantes y con cada palabra en mayúscula inicial. */
public final class NameNormalizer {

    private NameNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String collapsed = value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (collapsed.isEmpty()) {
            return collapsed;
        }

        return Arrays.stream(collapsed.split(" "))
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }
}
