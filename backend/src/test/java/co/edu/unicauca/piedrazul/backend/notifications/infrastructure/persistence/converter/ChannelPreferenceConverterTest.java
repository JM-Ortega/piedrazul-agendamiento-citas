package co.edu.unicauca.piedrazul.backend.notifications.infrastructure.persistence.converter;

import co.edu.unicauca.piedrazul.backend.notifications.domain.model.ChannelPreference;
import co.edu.unicauca.piedrazul.backend.notifications.domain.model.NotificationChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelPreferenceConverterTest {

    private final ChannelPreferenceConverter converter = new ChannelPreferenceConverter();

    // ─────────────────────────────────────────────
    // convertToDatabaseColumn
    // ─────────────────────────────────────────────

    @Test
    void convertToDatabaseColumnShouldReturnNullWhenAttributeIsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToDatabaseColumnShouldSerializePreferredOrder() {
        ChannelPreference preference = new ChannelPreference(
                List.of(NotificationChannel.WHATSAPP, NotificationChannel.EMAIL)
        );

        String json = converter.convertToDatabaseColumn(preference);

        assertThat(json).isEqualTo("{\"preferredOrder\":[\"WHATSAPP\",\"EMAIL\"]}");
    }

    // ─────────────────────────────────────────────
    // convertToEntityAttribute
    // ─────────────────────────────────────────────

    @Test
    void convertToEntityAttributeShouldReturnNullWhenDbDataIsNullOrBlank() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("  ")).isNull();
    }

    @Test
    void convertToEntityAttributeShouldDeserializePreferredOrder() {
        ChannelPreference preference = converter.convertToEntityAttribute(
                "{\"preferredOrder\":[\"SMS\",\"CONSOLE\"]}"
        );

        assertThat(preference.preferredOrder())
                .containsExactly(NotificationChannel.SMS, NotificationChannel.CONSOLE);
    }

    @Test
    void convertToEntityAttributeShouldThrowWhenJsonIsInvalid() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripShouldPreserveValue() {
        ChannelPreference original = new ChannelPreference(
                List.of(NotificationChannel.EMAIL, NotificationChannel.CONSOLE)
        );

        String json = converter.convertToDatabaseColumn(original);
        ChannelPreference restored = converter.convertToEntityAttribute(json);

        assertThat(restored).isEqualTo(original);
    }
}
