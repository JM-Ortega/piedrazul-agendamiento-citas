package co.edu.unicauca.piedrazul.backend.patients.domain;

import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatientTest {

    @Test
    void constructorShouldCreatePatientWhenDataIsValid() {
        UUID personId = UUID.randomUUID();
        Patient patient = buildPatient(personId);

        assertThat(patient.getPersonId()).isEqualTo(personId);
        assertThat(patient.getSex()).isEqualTo(Sex.MASCULINO);
        assertThat(patient.getBirthDate()).isEqualTo(LocalDate.of(2000, 1, 15));
        assertThat(patient.getGuardianPhone()).isEqualTo("3007654321");
    }

    @Test
    void constructorShouldCreatePatientWhenGuardianPhoneIsNull() {
        Patient patient = new Patient(
                UUID.randomUUID(),
                Sex.FEMENINO,
                LocalDate.of(1995, 5, 20),
                null
        );

        assertThat(patient.getGuardianPhone()).isNull();
    }

    @Test
    void constructorShouldThrowExceptionWhenPersonIdIsNull() {
        assertThatThrownBy(() -> new Patient(
                null,
                Sex.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321"
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("personId is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenSexIsNull() {
        assertThatThrownBy(() -> new Patient(
                UUID.randomUUID(),
                null,
                LocalDate.of(2000, 1, 15),
                "3007654321"
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("sex is required");
    }

    @Test
    void constructorShouldThrowExceptionWhenBirthDateIsNull() {
        assertThatThrownBy(() -> new Patient(
                UUID.randomUUID(),
                Sex.MASCULINO,
                null,
                "3007654321"
        )).isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("birthDate is required");
    }

    private Patient buildPatient(UUID personId) {
        return new Patient(
                personId,
                Sex.MASCULINO,
                LocalDate.of(2000, 1, 15),
                "3007654321"
        );
    }
}
