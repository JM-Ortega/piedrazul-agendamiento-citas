package co.edu.unicauca.piedrazul.backend.notifications.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static co.edu.unicauca.piedrazul.backend.notifications.application.DispatchTestSupport.VALIDATOR;
import static org.assertj.core.api.Assertions.assertThat;

/** Direcciones que el transporte puede entregar sin SMTPUTF8 y sin cambiar de destino. */
class RecipientEmailPolicyTest {

    private final RecipientEmailPolicy policy = new RecipientEmailPolicy(VALIDATOR);

    @ParameterizedTest
    @ValueSource(strings = {
            "ana.ruiz@correo.example.com",
            "ANA+citas@Correo.Example.COM",
            "o'neil@correo.example.com",
            "a_b-c.d@sub.correo.example.co",
    })
    void asciiAddressesAreKeptExactlyAsWritten(String email) {
        assertThat(policy.deliverableAddress(email)).contains(email);
    }

    @ParameterizedTest
    @CsvSource({
            "ana@clínica.example,       ana@xn--clnica-4va.example",
            "ana@CLÍNICA.example,       ana@xn--clnica-4va.example",
            "ana@españa.example,        ana@xn--espaa-rta.example",
            "ana@bücher.example,        ana@xn--bcher-kva.example",
            "ana@例え.example,           ana@xn--r8jz45g.example",
    })
    void internationalizedDomainsConvertToTheirAsciiForm(String email, String expected) {
        assertThat(policy.deliverableAddress(email)).contains(expected);
    }

    @Test
    void anAlreadyConvertedDomainIsKept() {
        assertThat(policy.deliverableAddress("ana@xn--clnica-4va.example")).contains("ana@xn--clnica-4va.example");
    }

    @Test
    void aDomainTypedInDecomposedFormConvertsToTheSameDestination() {
        // "í" escrita como i + acento combinante: es el mismo dominio.
        assertThat(policy.deliverableAddress("ana@clínica.example")).contains("ana@xn--clnica-4va.example");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ñandú@correo.example",
            "josé@correo.example",
            "用户@例え.example",
            "ana ruiz@correo.example",
    })
    void localPartsThatRequireSmtpUtf8AreUnusable(String email) {
        assertThat(policy.isUsable(email)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // ß y ς se convertirían en ss y σ: otro dominio.
            "ana@faß.example",
            "ana@straße.example",
            "ana@ςαρ.example",
            // Compatibilidad: ancho completo y ligaduras se pliegan a caracteres distintos.
            "ana@ｃorreo.example",
            "ana@ﬁnanzas.example",
            // Invisible: desaparecería al convertir.
            "ana@cor­reo.example",
            "ana@cor‍reo.example",
            // Separador no ASCII: cambiaría la estructura del dominio.
            "ana@correo。example",
    })
    void conversionsThatWouldChangeTheDomainAreRejected(String email) {
        assertThat(policy.deliverableAddress(email)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ana@clínica.invalid",
            "ana@ejemplo.invalid",
            "ana@EJEMPLO.INVALID",
    })
    void theReservedInvalidDomainIsRejectedInAnyForm(String email) {
        assertThat(policy.isUsable(email)).isFalse();
    }

    @Test
    void aConversionBeyondTheLabelLimitIsRejected() {
        assertThat(policy.isUsable("ana@" + "ñ".repeat(70) + ".example")).isFalse();
    }

    @Test
    void addressesThatCouldInjectCommandsAreRejected() {
        assertThat(policy.isUsable("ana@correo.example\r\nRCPT TO:<tercero@correo.example>")).isFalse();
        assertThat(policy.isUsable("ana>@correo.example")).isFalse();
    }

    @Test
    void aMissingOrBlankValueIsUnusable() {
        assertThat(policy.deliverableAddress(null)).isEmpty();
        assertThat(policy.deliverableAddress(" ")).isEmpty();
    }
}
