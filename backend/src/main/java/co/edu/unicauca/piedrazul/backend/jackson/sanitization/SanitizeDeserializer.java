package co.edu.unicauca.piedrazul.backend.jackson.sanitization;

import co.edu.unicauca.piedrazul.backend.jackson.normalization.NameNormalizer;
import co.edu.unicauca.piedrazul.backend.jackson.normalization.NormalizeName;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Limpia el HTML de un texto. Si el campo también tiene {@code @NormalizeName}, además lo
 * normaliza: Jackson usa uno solo de los dos deserializadores y ambos deben dar lo mismo.
 */
public class SanitizeDeserializer extends ValueDeserializer<String> {

    private final boolean normalizeName;

    public SanitizeDeserializer() {
        this(false);
    }

    private SanitizeDeserializer(boolean normalizeName) {
        this.normalizeName = normalizeName;
    }

    @Override
    public ValueDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        boolean normalize = property != null && property.getAnnotation(NormalizeName.class) != null;
        return normalize == normalizeName ? this : new SanitizeDeserializer(normalize);
    }

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        String cleaned = Sanitizer.clean(parser.getValueAsString());
        return normalizeName ? NameNormalizer.normalize(cleaned) : cleaned;
    }
}
