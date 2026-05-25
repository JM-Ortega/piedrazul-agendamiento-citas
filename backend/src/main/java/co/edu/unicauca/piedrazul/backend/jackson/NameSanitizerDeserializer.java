package co.edu.unicauca.piedrazul.backend.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

public class NameSanitizerDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {

        String value = p.getValueAsString();

        if (value == null) {
            return null;
        }

        value = value.trim();

        value = value.replaceAll("\\s+", " ");

        value = value.toLowerCase();

        return Arrays.stream(value.split(" "))
                .map(word ->
                        Character.toUpperCase(word.charAt(0))
                                + word.substring(1))
                .collect(Collectors.joining(" "));
    }
}