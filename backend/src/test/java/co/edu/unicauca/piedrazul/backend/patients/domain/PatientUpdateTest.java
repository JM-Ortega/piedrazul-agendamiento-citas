package co.edu.unicauca.piedrazul.backend.patients.domain;

import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatientUpdateTest {

    private final UUID personId = UUID.randomUUID();
    private final Patient patient = new Patient(personId, Sex.MASCULINO, LocalDate.of(2000, 1, 15), "3007654321");

    @Test
    void updateShouldReplaceOwnDataAndKeepIdentity() {
        patient.update(Sex.OTRO, LocalDate.of(1990, 3, 1), null);

        assertThat(patient.getPersonId()).isEqualTo(personId);
        assertThat(patient.getSex()).isEqualTo(Sex.OTRO);
        assertThat(patient.getBirthDate()).isEqualTo(LocalDate.of(1990, 3, 1));
        assertThat(patient.getGuardianPhone()).isNull();
    }

    @Test
    void updateShouldRejectNullSexWithoutChangingState() {
        assertThatThrownBy(() -> patient.update(null, LocalDate.of(1990, 3, 1), null))
                .isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("sex is required");

        assertThat(patient.getSex()).isEqualTo(Sex.MASCULINO);
        assertThat(patient.getBirthDate()).isEqualTo(LocalDate.of(2000, 1, 15));
    }

    @Test
    void updateShouldRejectNullBirthDateWithoutChangingState() {
        assertThatThrownBy(() -> patient.update(Sex.FEMENINO, null, "3001112233"))
                .isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("birthDate is required");

        assertThat(patient.getSex()).isEqualTo(Sex.MASCULINO);
        assertThat(patient.getGuardianPhone()).isEqualTo("3007654321");
    }
}
