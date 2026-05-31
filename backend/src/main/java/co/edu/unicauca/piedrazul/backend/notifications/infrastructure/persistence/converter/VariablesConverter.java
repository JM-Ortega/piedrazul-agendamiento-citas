package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.converter;

import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.exception.NotificationPersistenceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.HashMap;
import java.util.Map;

@Converter
public class VariablesConverter
        implements AttributeConverter<Map<String, String>, String> {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Map<String, String> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "{}";
        }

        try {
            return objectMapper.writeValueAsString(attribute);
        } catch (JsonProcessingException exception) {
            throw new NotificationPersistenceException(
                    "No fue posible serializar las variables de la notificación",
                    exception
            );
        }
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new HashMap<>();
        }

        try {
            return objectMapper.readValue(
                    dbData,
                    new TypeReference<>() {}
            );
        } catch (JsonProcessingException exception) {
            throw new NotificationPersistenceException(
                    "No fue posible deserializar las variables de la notificación",
                    exception
            );
        }
    }
}