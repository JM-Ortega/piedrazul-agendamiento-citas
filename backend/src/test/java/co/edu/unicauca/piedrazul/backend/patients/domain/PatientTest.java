package co.edu.unicauca.piedrazul.backend.patients.domain;

import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatientTest {

    @Test
    void constructorShouldCreatePatientWhenDataIsValidAndUserIdIsNull() {
        Patient patient = buildPatient(null);

        assertThat(patient.getId()).isNull();
        assertThat(patient.getUserId()).isNull();
        assertThat(patient.getDocumentType()).isEqualTo(DocumentType.CEDULA);
        assertThat(patient.getDocumentNumber()).isEqualTo("123456789");
        assertThat(patient.getFirstName()).isEqualTo("Juan");
        assertThat(patient.getLastName()).isEqualTo("Perez");
        assertThat(patient.getPhone()).isEqualTo("3001234567");
        assertThat(patient.getEmail()).isEqualTo("juan@example.com");
        assertThat(patient.getGender()).isEqualTo(Gender.MASCULINO);
        assertThat(patient.getBirthDate()).isEqualTo(LocalDate.of(2000, 1, 15));
        assertThat(patient.getGuardianPhone()).isEqualTo("3007654321");
        assertThat(patient.hasUserAccount()).isFalse();
    }

    @Test
    void constructorShouldCreatePatientWhenDataIsValidAndUserIdIsPresent() {
        UUID userId = UUID.randomUUID();
        Patient patient = buildPatient(userId);

        assertThat(patient.getUserId()).isEqualTo(userId);
        assertThat(patient.hasUserAccount()).isTrue();
    }

    @Test
    void constructorShouldThrowExceptionWhenDocumentTypeIsNull() {
        assertThatThrownBy(() -> new Patient(
                null,
                "123456789",
                "Juan",
                "Perez",
                "3001234567",
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("documentType is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenDocumentNumberIsNull() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                null,
                "Juan",
                "Perez",
                "3001234567",
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("documentNumber is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenDocumentNumberIsBlank() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                "   ",
                "Juan",
                "Perez",
                "3001234567",
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("documentNumber is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenFirstNameIsNull() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                "123456789",
                null,
                "Perez",
                "3001234567",
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("firstName is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenFirstNameIsBlank() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                "123456789",
                "   ",
                "Perez",
                "3001234567",
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("firstName is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenLastNameIsNull() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                "123456789",
                "Juan",
                null,
                "3001234567",
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("lastName is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenLastNameIsBlank() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                "123456789",
                "Juan",
                "   ",
                "3001234567",
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("lastName is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenPhoneIsNull() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                "123456789",
                "Juan",
                "Perez",
                null,
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("phone is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenPhoneIsBlank() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                "123456789",
                "Juan",
                "Perez",
                "   ",
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("phone is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenGenderIsNull() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                "123456789",
                "Juan",
                "Perez",
                "3001234567",
                "juan@example.com",
                null,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("gender is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenBirthDateIsNull() {
        assertThatThrownBy(() -> new Patient(
                DocumentType.CEDULA,
                "123456789",
                "Juan",
                "Perez",
                "3001234567",
                "juan@example.com",
                Gender.MASCULINO,
                null,
                "3007654321",
                null
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("birthDate is required");
    }

    @Test
    void hasUserAccountShouldReturnFalseWhenUserIdIsNull() {
        Patient patient = buildPatient(null);

        assertThat(patient.hasUserAccount()).isFalse();
    }

    @Test
    void hasUserAccountShouldReturnTrueWhenUserIdIsPresent() {
        Patient patient = buildPatient(UUID.randomUUID());

        assertThat(patient.hasUserAccount()).isTrue();
    }

    /*
    @Test
    void linkUserShouldAssignUserIdWhenPatientDoesNotHaveLinkedUser() {
        Patient patient = buildPatient(null);
        UUID userId = UUID.randomUUID();

        patient.linkUser(userId);

        assertThat(patient.getUserId()).isEqualTo(userId);
        assertThat(patient.hasUserAccount()).isTrue();
    }

    @Test
    void linkUserShouldThrowExceptionWhenUserIdIsNull() {
        Patient patient = buildPatient(null);

        assertThatThrownBy(() -> patient.linkUser(null))
                .isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("userId cannot be null");
    }

    @Test
    void linkUserShouldThrowExceptionWhenPatientAlreadyHasLinkedUser() {
        Patient patient = buildPatient(UUID.randomUUID());

        assertThatThrownBy(() -> patient.linkUser(UUID.randomUUID()))
                .isInstanceOf(PatientAlreadyLinkedUserException.class);
    }
     */

    private Patient buildPatient(UUID userId) {
        return new Patient(
                DocumentType.CEDULA,
                "123456789",
                "Juan",
                "Perez",
                "3001234567",
                "juan@example.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321",
                userId
        );
    }
}