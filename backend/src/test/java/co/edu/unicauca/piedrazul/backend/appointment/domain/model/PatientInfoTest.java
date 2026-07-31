package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import co.edu.unicauca.piedrazul.backend.appointment.exception.GuardianRequiredException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InconsistentPatientInfoException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidBirthDateException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidDocumentException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidEmailException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidPatientInfoException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidPersonNameException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.InvalidPhoneException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatientInfoTest {

    // ─────────────────────────────────────────────
    // Construcción válida — caso adulto completo
    // ─────────────────────────────────────────────

    @Test
    void ofShouldCreatePatientInfoWhenAllFieldsAreValidForAdult() {
        PatientInfo info = buildAdulto();

        assertThat(info.getFirstName()).isEqualTo("Carlos");
        assertThat(info.getLastName()).isEqualTo("Gomez");
        assertThat(info.getDocumentType()).isEqualTo(DocumentType.CEDULA);
        assertThat(info.getDocumentNumber()).isEqualTo("12345678");
        assertThat(info.getPhone()).isEqualTo("3001234567");
        assertThat(info.getGender()).isEqualTo(Gender.MASCULINO);
        assertThat(info.getEmail()).isEqualTo("carlos@correo.com");
        assertThat(info.getGuardianPhone()).isNull();
        assertThat(info.isMinor()).isFalse();
    }

    @Test
    void ofShouldCreatePatientInfoWhenAdultHasNoEmail() {
        // El email es opcional para adultos
        PatientInfo info = PatientInfo.of(
                DocumentType.CEDULA,
                "12345678",
                "Carlos",
                "Gomez",
                "3001234567",
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                null,
                null
        );

        assertThat(info.getEmail()).isNull();
    }

    @Test
    void ofShouldTrimWhitespaceFromStringFields() {
        // Los campos de texto deben normalizarse (trim)
        PatientInfo info = PatientInfo.of(
                DocumentType.CEDULA,
                "  12345678  ",
                "  Carlos  ",
                "  Gomez  ",
                "  3001234567  ",
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "  carlos@correo.com  ",
                null
        );

        assertThat(info.getDocumentNumber()).isEqualTo("12345678");
        assertThat(info.getFirstName()).isEqualTo("Carlos");
        assertThat(info.getLastName()).isEqualTo("Gomez");
        assertThat(info.getPhone()).isEqualTo("3001234567");
        assertThat(info.getEmail()).isEqualTo("carlos@correo.com");
    }

    // ─────────────────────────────────────────────
    // Construcción válida — menor de edad
    // ─────────────────────────────────────────────

    @Test
    void ofShouldCreatePatientInfoWhenMinorHasValidDocumentAndGuardian() {
        PatientInfo info = buildMenor();

        assertThat(info.isMinor()).isTrue();
        assertThat(info.getDocumentType()).isEqualTo(DocumentType.TARJETA_IDENTIDAD);
        assertThat(info.getGuardianPhone()).isEqualTo("3009876543");
    }

    @Test
    void ofShouldCreatePatientInfoWhenMinorHasRegistroNacimiento() {
        PatientInfo info = PatientInfo.of(
                DocumentType.REGISTRO_NACIMIENTO,
                "12345678",
                "Sofia",
                "Rios",
                "3001234567",
                Gender.FEMENINO,
                LocalDate.now().minusYears(5),
                null,
                "3009999999"
        );

        assertThat(info.isMinor()).isTrue();
        assertThat(info.getDocumentType()).isEqualTo(DocumentType.REGISTRO_NACIMIENTO);
    }

    // ─────────────────────────────────────────────
    // Campos obligatorios nulos / en blanco
    // ─────────────────────────────────────────────

    @Test
    void ofShouldThrowWhenDocumentTypeIsNull() {
        assertThatThrownBy(() -> PatientInfo.of(
                null,
                "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPatientInfoException.class)
                .hasMessageContaining("tipo de documento es obligatorio");
    }

    @Test
    void ofShouldThrowWhenDocumentNumberIsNull() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                null, "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPatientInfoException.class)
                .hasMessageContaining("número de documento es obligatorio");
    }

    @Test
    void ofShouldThrowWhenDocumentNumberIsBlank() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "   ", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPatientInfoException.class)
                .hasMessageContaining("número de documento es obligatorio");
    }

    @Test
    void ofShouldThrowWhenFirstNameIsNull() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", null, "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPatientInfoException.class)
                .hasMessageContaining("nombre es obligatorio");
    }

    @Test
    void ofShouldThrowWhenLastNameIsBlank() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "   ", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPatientInfoException.class)
                .hasMessageContaining("apellido es obligatorio");
    }

    @Test
    void ofShouldThrowWhenPhoneIsNull() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", null,
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPatientInfoException.class)
                .hasMessageContaining("celular es obligatorio");
    }

    @Test
    void ofShouldThrowWhenGenderIsNull() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", "3001234567",
                null, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPatientInfoException.class)
                .hasMessageContaining("género es obligatorio");
    }

    @Test
    void ofShouldThrowWhenBirthDateIsNull() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, null, null, null
        ))
                .isInstanceOf(InvalidPatientInfoException.class)
                .hasMessageContaining("fecha de nacimiento es obligatoria");
    }

    // ─────────────────────────────────────────────
    // Validación de documento
    // ─────────────────────────────────────────────

    @Test
    void ofShouldThrowWhenDocumentNumberHasFewerThanSixDigits() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("entre 6 y 12 dígitos");
    }

    @Test
    void ofShouldThrowWhenDocumentNumberHasMoreThanTwelveDigits() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "1234567890123", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("entre 6 y 12 dígitos");
    }

    @Test
    void ofShouldThrowWhenDocumentNumberContainsLetters() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "123ABC78", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidDocumentException.class);
    }

    // ─────────────────────────────────────────────
    // Validación de nombre y apellido
    // ─────────────────────────────────────────────

    @Test
    void ofShouldThrowWhenFirstNameContainsNumbers() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos3", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPersonNameException.class)
                .hasMessageContaining("caracteres inválidos");
    }

    @Test
    void ofShouldThrowWhenLastNameContainsSpecialCharacters() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "G0mez!", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPersonNameException.class)
                .hasMessageContaining("caracteres inválidos");
    }

    @Test
    void ofShouldAcceptNamesWithAccentsAndSpaces() {
        // Nombres compuestos y con tildes son válidos
        PatientInfo info = PatientInfo.of(
                DocumentType.CEDULA,
                "12345678",
                "María José",
                "Ñúñez López",
                "3001234567",
                Gender.FEMENINO,
                LocalDate.of(1990, 6, 15),
                null,
                null
        );

        assertThat(info.getFirstName()).isEqualTo("María José");
        assertThat(info.getLastName()).isEqualTo("Ñúñez López");
    }

    // ─────────────────────────────────────────────
    // Validación de teléfono
    // ─────────────────────────────────────────────

    @Test
    void ofShouldThrowWhenPhoneHasFewerThanSevenDigits() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", "123456",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPhoneException.class)
                .hasMessageContaining("entre 7 y 15 dígitos");
    }

    @Test
    void ofShouldThrowWhenPhoneHasMoreThanFifteenDigits() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", "3001234567890123",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPhoneException.class)
                .hasMessageContaining("entre 7 y 15 dígitos");
    }

    @Test
    void ofShouldThrowWhenPhoneContainsLetters() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", "300ABC4567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InvalidPhoneException.class);
    }

    // ─────────────────────────────────────────────
    // Validación de fecha de nacimiento
    // ─────────────────────────────────────────────

    @Test
    void ofShouldThrowWhenBirthDateIsInTheFuture() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.now().plusDays(1), null, null
        ))
                .isInstanceOf(InvalidBirthDateException.class)
                .hasMessageContaining("no puede ser futura");
    }

    @Test
    void ofShouldAcceptBirthDateAsToday() {
        // Nacer hoy es válido (no es fecha futura)
        PatientInfo info = PatientInfo.of(
                DocumentType.REGISTRO_NACIMIENTO,
                "12345678",
                "Bebe",
                "Nuevo",
                "3001234567",
                Gender.MASCULINO,
                LocalDate.now(),
                null,
                "3009999999"
        );

        assertThat(info.getBirthDate()).isEqualTo(LocalDate.now());
    }

    // ─────────────────────────────────────────────
    // Validación de email
    // ─────────────────────────────────────────────

    @Test
    void ofShouldThrowWhenEmailHasInvalidFormat() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "correo-sin-arroba", null
        ))
                .isInstanceOf(InvalidEmailException.class)
                .hasMessageContaining("formato válido");
    }

    @Test
    void ofShouldThrowWhenEmailHasNoTld() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "correo@dominio", null
        ))
                .isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void ofShouldAcceptNullEmailAsOptional() {

        // buildAdulto usa email válido, pero validamos que null también pasa
        PatientInfo sinEmail = PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        );

        assertThat(sinEmail.getEmail()).isNull();
    }

    // ─────────────────────────────────────────────
    // Reglas para menores de edad
    // ─────────────────────────────────────────────

    @Test
    void ofShouldThrowWhenMinorHasNoGuardianPhone() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.TARJETA_IDENTIDAD,
                "12345678", "Pedro", "Ruiz", "3001234567",
                Gender.MASCULINO, LocalDate.now().minusYears(10), null, null
        ))
                .isInstanceOf(GuardianRequiredException.class)
                .hasMessageContaining("acudiente");
    }

    @Test
    void ofShouldThrowWhenMinorHasBlankGuardianPhone() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.TARJETA_IDENTIDAD,
                "12345678", "Pedro", "Ruiz", "3001234567",
                Gender.MASCULINO, LocalDate.now().minusYears(10), null, "   "
        ))
                .isInstanceOf(GuardianRequiredException.class)
                .hasMessageContaining("acudiente");
    }

    @Test
    void ofShouldThrowWhenMinorHasCedula() {
        // Un menor no puede tener cédula
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.CEDULA,
                "12345678", "Pedro", "Ruiz", "3001234567",
                Gender.MASCULINO, LocalDate.now().minusYears(10), null, "3009999999"
        ))
                .isInstanceOf(InconsistentPatientInfoException.class)
                .hasMessageContaining("menor no puede tener cédula");
    }

    // ─────────────────────────────────────────────
    // Reglas para adultos
    // ─────────────────────────────────────────────

    @Test
    void ofShouldThrowWhenAdultHasTarjetaIdentidad() {
        // Un adulto no debería tener tarjeta de identidad
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.TARJETA_IDENTIDAD,
                "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InconsistentPatientInfoException.class)
                .hasMessageContaining("adulto no debería tener este tipo de documento");
    }

    @Test
    void ofShouldThrowWhenAdultHasRegistroNacimiento() {
        assertThatThrownBy(() -> PatientInfo.of(
                DocumentType.REGISTRO_NACIMIENTO,
                "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), null, null
        ))
                .isInstanceOf(InconsistentPatientInfoException.class)
                .hasMessageContaining("adulto no debería tener este tipo de documento");
    }

    // ─────────────────────────────────────────────
    // isMinor
    // ─────────────────────────────────────────────

    @Test
    void isMinorShouldReturnTrueWhenPatientIsUnder18() {
        PatientInfo menor = buildMenor();
        assertThat(menor.isMinor()).isTrue();
    }

    @Test
    void isMinorShouldReturnFalseWhenPatientIsAdult() {
        PatientInfo adulto = buildAdulto();
        assertThat(adulto.isMinor()).isFalse();
    }

    // ─────────────────────────────────────────────
    // equals y hashCode (ValueObject)
    // ─────────────────────────────────────────────

    @Test
    void equalsShouldReturnTrueWhenAllFieldsAreIdentical() {
        PatientInfo a = buildAdulto();
        PatientInfo b = buildAdulto();

        assertThat(a).isEqualTo(b);
    }

    @Test
    void equalsShouldReturnFalseWhenDocumentNumberDiffers() {
        PatientInfo a = buildAdulto();
        PatientInfo b = PatientInfo.of(
                DocumentType.CEDULA,
                "99999999",     // diferente
                "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15),
                "carlos@correo.com", null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashCodeShouldBeEqualForIdenticalPatientInfo() {
        PatientInfo a = buildAdulto();
        PatientInfo b = buildAdulto();

        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // ─────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────

    private PatientInfo buildAdulto() {
        return PatientInfo.of(
                DocumentType.CEDULA,
                "12345678",
                "Carlos",
                "Gomez",
                "3001234567",
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null
        );
    }

    private PatientInfo buildMenor() {
        return PatientInfo.of(
                DocumentType.TARJETA_IDENTIDAD,
                "12345678",
                "Pedro",
                "Ruiz",
                "3001234567",
                Gender.MASCULINO,
                LocalDate.now().minusYears(10),
                null,
                "3009876543"
        );
    }
}
