package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.converter;

import co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.exception.NotificationPersistenceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VariablesConverterTest {

    private final VariablesConverter converter = new VariablesConverter();

    // ─────────────────────────────────────────────
    // convertToDatabaseColumn
    // ─────────────────────────────────────────────

    @Test
    void convertToDatabaseColumnShouldReturnEmptyJsonObjectWhenAttributeIsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("{}");
    }

    @Test
    void convertToDatabaseColumnShouldReturnEmptyJsonObjectWhenAttributeIsEmpty() {
        assertThat(converter.convertToDatabaseColumn(Map.of())).isEqualTo("{}");
    }

    @Test
    void convertToDatabaseColumnShouldSerializeEntries() {
        String json = converter.convertToDatabaseColumn(Map.of("code", "123456"));

        assertThat(json).isEqualTo("{\"code\":\"123456\"}");
    }

    // ─────────────────────────────────────────────
    // convertToEntityAttribute
    // ─────────────────────────────────────────────

    @Test
    void convertToEntityAttributeShouldReturnEmptyMapWhenDbDataIsNullOrBlank() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute("  ")).isEmpty();
    }

    @Test
    void convertToEntityAttributeShouldDeserializeAsStringToStringMap() {
        Map<String, String> variables = converter.convertToEntityAttribute(
                "{\"patientName\":\"Ana\",\"date\":\"2026-07-16\"}"
        );

        assertThat(variables)
                .containsEntry("patientName", "Ana")
                .containsEntry("date", "2026-07-16");
        variables.values().forEach(value -> assertThat(value).isInstanceOf(String.class));
    }

    @Test
    void convertToEntityAttributeShouldThrowWhenJsonIsInvalid() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not-json"))
                .isInstanceOf(NotificationPersistenceException.class);
    }

    @Test
    void roundTripShouldPreserveValue() {
        Map<String, String> original = Map.of("code", "654321", "expirationMinutes", "5");

        String json = converter.convertToDatabaseColumn(original);
        Map<String, String> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).isEqualTo(original);
    }
}
