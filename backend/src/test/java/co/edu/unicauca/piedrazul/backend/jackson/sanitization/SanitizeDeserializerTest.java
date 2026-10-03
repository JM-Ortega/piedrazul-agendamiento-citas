package co.edu.unicauca.piedrazul.backend.jackson.sanitization;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.ConfirmLinkUserAccountRequest;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizeDeserializerTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Test
    void shouldSanitizeOnlyAnnotatedFieldsOnDeserialization() throws Exception {
        String json = """
                {
                  "identification": "<script>alert('1')</script>123",
                  "code": "<b>ABC123</b>",
                  "password": "<tag>myP@ss</tag>"
                }
                """;

        ConfirmLinkUserAccountRequest request = objectMapper.readValue(json, ConfirmLinkUserAccountRequest.class);

        assertThat(request.getIdentification()).isEqualTo("123");
        assertThat(request.getCode()).isEqualTo("<b>ABC123</b>");
        assertThat(request.getPassword()).isEqualTo("<tag>myP@ss</tag>");
    }

    @Test
    void shouldRemoveDangerousAttributes() throws Exception {
        String json = """
        {
          "identification": "<img src=x onerror=alert(1)>123"
        }
    """;

        ConfirmLinkUserAccountRequest request =
                objectMapper.readValue(json, ConfirmLinkUserAccountRequest.class);

        assertThat(request.getIdentification()).doesNotContain("onerror");
    }

    @Test
    void shouldPreserveAllowedHtml() throws Exception {
        String json = """
        {
          "identification": "<b>123</b>"
        }
    """;

        ConfirmLinkUserAccountRequest request =
                objectMapper.readValue(json, ConfirmLinkUserAccountRequest.class);

        assertThat(request.getIdentification()).contains("<b>");
    }

    @Test
    void shouldSanitizeNestedObjects() throws Exception {
        String json = """
        {
          "nested": {
            "documentNumber": "<script>alert(1)</script>456"
          }
        }
    """;

        NestedWrapper request = objectMapper.readValue(json, NestedWrapper.class);

        assertThat(request.getNested().getDocumentNumber()).isEqualTo("456");
    }

    @Test
    void shouldSanitizeCollections() throws Exception {
        String json = """
        {
          "values": [
            {"documentNumber": "<script>1</script>"},
            {"documentNumber": "<b>safe</b>"}
          ]
        }
    """;

        CollectionWrapper request = objectMapper.readValue(json, CollectionWrapper.class);

        assertThat(request.getValues().get(0).getDocumentNumber()).isEqualTo("");
        assertThat(request.getValues().get(1).getDocumentNumber()).isEqualTo("<b>safe</b>");
    }

    @Test
    void shouldNotSanitizeNonAnnotatedField() throws Exception {
        String json = """
        {
          "password": "<script>alert(1)</script>"
        }
    """;

        ConfirmLinkUserAccountRequest request =
                objectMapper.readValue(json, ConfirmLinkUserAccountRequest.class);

        assertThat(request.getPassword()).contains("<script>");
    }

    @Test
    void sanitizerShouldReturnNullWhenInputIsNull() {
        assertThat(Sanitizer.clean(null)).isNull();
    }

    private static class NestedWrapper {
      private NestedValue nested;

      public NestedValue getNested() {
        return nested;
      }
    }

    private static class CollectionWrapper {
      private List<NestedValue> values;

      public List<NestedValue> getValues() {
        return values;
      }
    }

    private static class NestedValue {
      @Sanitize
      private String documentNumber;

      public String getDocumentNumber() {
        return documentNumber;
      }
    }
}
