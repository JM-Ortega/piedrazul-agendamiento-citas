package co.edu.unicauca.piedrazul.backend.jackson.normalization;

import co.edu.unicauca.piedrazul.backend.jackson.sanitization.Sanitizer;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Limpia y normaliza un nombre. Siempre limpia el HTML: si el campo también tiene
 * {@code @Sanitize}, Jackson usa uno solo de los dos deserializadores y ambos deben dar lo mismo.
 */
public class NameSanitizerDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        return NameNormalizer.normalize(Sanitizer.clean(parser.getValueAsString()));
    }
}
