package co.edu.unicauca.piedrazul.backend.config.security.validation;

import co.edu.unicauca.piedrazul.backend.jackson.validation.ValidDocument;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidDocumentValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptCedulaWhenNumericAndLengthBetween6And10() {
        TestDocumentDto dto = new TestDocumentDto("1234567890", DocType.CEDULA);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void shouldRejectCedulaWhenContainsLetters() {
        TestDocumentDto dto = new TestDocumentDto("12AB56", DocType.CEDULA);
        assertThat(validator.validate(dto)).isNotEmpty();
    }

    @Test
    void shouldAcceptTarjetaIdentidadWith10To11Digits() {
        TestDocumentDto dto = new TestDocumentDto("12345678901", DocType.TARJETA_IDENTIDAD);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void shouldAcceptRegistroNacimientoWith8To20Digits() {
        TestDocumentDto dto = new TestDocumentDto("12345678123456789012", DocType.REGISTRO_NACIMIENTO);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void shouldAcceptPasaporteAlphanumericWith6To9Chars() {
        TestDocumentDto dto = new TestDocumentDto("A12B34C", DocType.PASAPORTE);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @Test
    void shouldIgnoreWhenDocumentNumberIsNull() {
        TestDocumentDto dto = new TestDocumentDto(null, DocType.CEDULA);
        assertThat(validator.validate(dto)).isEmpty();
    }

    @ValidDocument
    private record TestDocumentDto(String documentNumber, DocType documentType) {
    }

    private enum DocType {
        CEDULA,
        TARJETA_IDENTIDAD,
        REGISTRO_NACIMIENTO,
        PASAPORTE
    }
}
