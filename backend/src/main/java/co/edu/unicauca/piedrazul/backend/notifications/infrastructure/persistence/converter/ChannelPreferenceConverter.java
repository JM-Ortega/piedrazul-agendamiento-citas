package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.converter;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ChannelPreference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ChannelPreferenceConverter implements AttributeConverter<ChannelPreference, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String convertToDatabaseColumn(ChannelPreference attribute) {
        if (attribute == null) {
            return null;
        }

        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Error serializando ChannelPreference a JSON",
                    exception
            );
        }
    }

    @Override
    public ChannelPreference convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(dbData, ChannelPreference.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Error deserializando ChannelPreference desde JSON",
                    exception
            );
        }
    }
}