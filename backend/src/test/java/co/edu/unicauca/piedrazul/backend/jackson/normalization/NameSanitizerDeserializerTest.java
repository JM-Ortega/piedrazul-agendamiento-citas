package co.edu.unicauca.piedrazul.backend.jackson.normalization;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NameSanitizerDeserializer - Tests Unitarios")
class NameSanitizerDeserializerTest {

    private NameSanitizerDeserializer deserializer;
    private JsonParser jsonParser;
    private DeserializationContext context;

    @BeforeEach
    void setUp() {
        deserializer = new NameSanitizerDeserializer();
        jsonParser   = mock(JsonParser.class);
        context      = mock(DeserializationContext.class);
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private String deserialize(String raw) {
        when(jsonParser.getValueAsString()).thenReturn(raw);
        return deserializer.deserialize(jsonParser, context);
    }

    // =========================================================================
    // Caso nulo
    // =========================================================================

    @Nested
    @DisplayName("Cuando el valor es null")
    class NullValueTests {

        @Test
        @DisplayName("Retorna null sin lanzar excepción")
        void deserialize_null_returnsNull() {
            assertThat(deserialize(null)).isNull();
        }
    }

    // =========================================================================
    // Capitalización
    // =========================================================================

    @Nested
    @DisplayName("Capitalización de palabras")
    class CapitalizationTests {

        @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
        @CsvSource({
                "juan,             Juan",
                "JUAN,             Juan",
                "jUaN,             Juan",
                "juan perez,       Juan Perez",
                "JUAN PEREZ,       Juan Perez",
                "ana maría lópez,  Ana María López"
        })
        @DisplayName("Capitaliza la primera letra de cada palabra y el resto en minúsculas")
        void deserialize_variousCases_capitalizesCorrectly(String input, String expected) {
            assertThat(deserialize(input)).isEqualTo(expected);
        }
    }

    // =========================================================================
    // Espacios en blanco
    // =========================================================================

    @Nested
    @DisplayName("Manejo de espacios en blanco")
    class WhitespaceTests {

        @Test
        @DisplayName("Elimina espacios al inicio y al final (trim)")
        void deserialize_leadingAndTrailingSpaces_trimsCorrectly() {
            assertThat(deserialize("  juan  ")).isEqualTo("Juan");
        }

        @Test
        @DisplayName("Colapsa múltiples espacios internos en uno solo")
        void deserialize_multipleInternalSpaces_collapsesToSingle() {
            assertThat(deserialize("juan   perez")).isEqualTo("Juan Perez");
        }

        @Test
        @DisplayName("Combina trim y colapso de espacios internos")
        void deserialize_mixedWhitespace_sanitizesCompletely() {
            assertThat(deserialize("  juan   perez  ")).isEqualTo("Juan Perez");
        }

        @Test
        @DisplayName("Mantiene una sola palabra sin espacios extra")
        void deserialize_singleWordNoSpaces_returnsCapitalized() {
            assertThat(deserialize("maria")).isEqualTo("Maria");
        }
    }

    // =========================================================================
    // Cadena vacía
    // =========================================================================

    @Nested
    @DisplayName("Cadena vacía")
    class EmptyStringTests {

        @Test
        @DisplayName("Retorna cadena vacía; @NotBlank la rechaza después")
        void deserialize_emptyString_returnsEmpty() {
            assertThat(deserialize("")).isEmpty();
        }

        @Test
        @DisplayName("Retorna cadena vacía con solo espacios (queda vacía tras trim)")
        void deserialize_onlySpaces_returnsEmpty() {
            assertThat(deserialize("   ")).isEmpty();
        }
    }

    // =========================================================================
    // Nombres compuestos y con caracteres especiales
    // =========================================================================

    @Nested
    @DisplayName("Nombres compuestos y caracteres especiales")
    class CompoundNamesTests {

        @ParameterizedTest(name = "\"{0}\" → \"{1}\"")
        @CsvSource({
                "luis angel garcia torres, Luis Angel Garcia Torres",
                "MARÍA DEL CARMEN,          María Del Carmen",
                "jose de la cruz,           Jose De La Cruz"
        })
        @DisplayName("Capitaliza cada palabra en nombres compuestos")
        void deserialize_compoundNames_capitalizesEachWord(String input, String expected) {
            assertThat(deserialize(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("Palabras con números conservan su forma salvo la capitalización inicial")
        void deserialize_wordWithDigits_capitalizesFirstChar() {
            // "3rd" → "3rd" (Character.toUpperCase('3') == '3')
            assertThat(deserialize("calle 3rd norte")).isEqualTo("Calle 3rd Norte");
        }
    }

    // =========================================================================
    // Integridad: el resultado nunca tiene espacios dobles ni extremos
    // =========================================================================

    @Nested
    @DisplayName("Integridad del resultado")
    class OutputIntegrityTests {

        @Test
        @DisplayName("El resultado no contiene espacios al inicio ni al final")
        void deserialize_result_hasNoLeadingOrTrailingSpaces() {
            String result = deserialize("  ana  maria  ");
            assertThat(result).doesNotStartWith(" ").doesNotEndWith(" ");
        }

        @Test
        @DisplayName("El resultado no contiene espacios dobles internos")
        void deserialize_result_hasNoDoubleSpaces() {
            String result = deserialize("juan   carlos   perez");
            assertThat(result).doesNotContain("  ");
        }

        @Test
        @DisplayName("El resultado no contiene letras mayúsculas en posición distinta a la primera de cada palabra")
        void deserialize_result_onlyFirstLetterUpperCase() {
            String result = deserialize("PEDRO ANTONIO RAMIREZ");
            for (String word : result.split(" ")) {
                if (word.length() > 1) {
                    assertThat(word.substring(1)).isEqualTo(word.substring(1).toLowerCase());
                }
            }
        }
    }
}