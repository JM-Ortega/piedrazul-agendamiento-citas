package co.edu.unicauca.piedrazul.backend.jackson.sanitization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quita el HTML peligroso sin escapar el texto: lo que se guarda es lo que la persona escribió,
 * menos las etiquetas y atributos no permitidos.
 */
class SanitizerTest {

    @Test
    void nullStaysNull() {
        assertThat(Sanitizer.clean(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Pérez & Hijos",
            "presión < 120 y > 80",
            "dosis: 5mg > 2mg",
            "Ñoño \"apodo\" O'Connor",
            "texto sin nada especial"
    })
    void plainTextIsKeptExactlyAsTyped(String text) {
        assertThat(Sanitizer.clean(text)).isEqualTo(text);
    }

    @Test
    void entitiesTypedByThePersonAreNotDecoded() {
        // Decodificarlas convertiría "&lt;script&gt;" en una etiqueta real.
        assertThat(Sanitizer.clean("&lt;script&gt;alert(1)&lt;/script&gt;"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void scriptsAreRemoved() {
        assertThat(Sanitizer.clean("<script>alert(1)</script>Hola")).isEqualTo("Hola");
    }

    @Test
    void dangerousAttributesAndTagsAreRemoved() {
        assertThat(Sanitizer.clean("<img src=x onerror=alert(1)>123")).isEqualTo("123");
        assertThat(Sanitizer.clean("<b onclick=\"alert(1)\">hola</b>")).isEqualTo("<b>hola</b>");
    }

    @Test
    void javascriptLinksLoseTheirTarget() {
        assertThat(Sanitizer.clean("<a href=\"javascript:alert(1)\">clic</a>"))
                .doesNotContainIgnoringCase("javascript");
    }

    @Test
    void basicFormattingIsKept() {
        assertThat(Sanitizer.clean("<b>importante</b> y <i>nota</i>"))
                .isEqualTo("<b>importante</b> y <i>nota</i>");
    }

    @Test
    void textAroundRemovedTagsKeepsItsCharacters() {
        assertThat(Sanitizer.clean("presión < 120 <script>x</script>& estable"))
                .isEqualTo("presión < 120 & estable");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<<script>script>alert(1)<</script>/script>",
            "<scr<script>ipt>alert(1)</scr</script>ipt>",
            "<<img src=x onerror=alert(1)>img src=x onerror=alert(1)>",
            "<<b>script>alert(1)<</b>/script>"
    })
    void nestedTagsCannotRebuildAScript(String payload) {
        String cleaned = Sanitizer.clean(payload);

        assertThat(cleaned).doesNotContainIgnoringCase("<script")
                .doesNotContainIgnoringCase("onerror")
                .doesNotContainIgnoringCase("<img");
        assertThat(Sanitizer.clean(cleaned)).as("limpiar dos veces no cambia el resultado").isEqualTo(cleaned);
    }
}
