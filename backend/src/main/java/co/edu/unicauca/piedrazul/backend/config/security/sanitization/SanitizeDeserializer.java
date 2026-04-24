package co.edu.unicauca.piedrazul.backend.config.security.sanitization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

public class SanitizeDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        return Sanitizer.clean(parser.getValueAsString());
    }
}
