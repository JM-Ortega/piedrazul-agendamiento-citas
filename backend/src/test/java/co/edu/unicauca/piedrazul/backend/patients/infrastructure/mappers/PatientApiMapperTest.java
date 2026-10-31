package co.edu.unicauca.piedrazul.backend.patients.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PatientApiMapperTest {

    @Test
    void toPatientDataShouldMapAllFieldsCorrectly() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        Patient patient = new Patient(
                personId,
                Sex.MASCULINO,
                LocalDate.of(2000, 1, 1),
                "111"
        );

        PersonSummary person = new PersonSummary(
                personId,
                userId,
                IdentificationType.CEDULA,
                "123",
                "Juan",
                "Perez",
                "300",
                "mail@test.com"
        );

        PatientData result = PatientApiMapper.toPatientData(patient, person);

        assertThat(result).isNotNull();
        assertThat(result.personId()).isEqualTo(personId);
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.identificationType()).isEqualTo(IdentificationType.CEDULA);
        assertThat(result.identification()).isEqualTo("123");
        assertThat(result.firstName()).isEqualTo("Juan");
        assertThat(result.lastName()).isEqualTo("Perez");
        assertThat(result.phone()).isEqualTo("300");
        assertThat(result.email()).isEqualTo("mail@test.com");
        assertThat(result.sex()).isEqualTo(PatientSex.MASCULINO);
        assertThat(result.birthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(result.guardianPhone()).isEqualTo("111");
    }

    @Test
    void toPatientDataShouldThrowExceptionWhenPatientIsNull() {
        PersonSummary person = new PersonSummary(
                UUID.randomUUID(), null, IdentificationType.CEDULA, "123",
                "Juan", "Perez", "300", "mail@test.com"
        );

        assertThatThrownBy(() -> PatientApiMapper.toPatientData(null, person))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Patient cannot be null");
    }

    @Test
    void toPatientDataShouldThrowExceptionWhenPersonIsNull() {
        Patient patient = new Patient(
                UUID.randomUUID(), Sex.MASCULINO, LocalDate.of(2000, 1, 1), "111"
        );

        assertThatThrownBy(() -> PatientApiMapper.toPatientData(patient, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PersonSummary cannot be null");
    }

    // =========================
    // Sex mappings
    // =========================

    @Test
    void toDomainSexShouldMapCorrectly() {
        assertThat(PatientApiMapper.toDomainSex(PatientSex.MASCULINO))
                .isEqualTo(Sex.MASCULINO);

        assertThat(PatientApiMapper.toDomainSex(PatientSex.FEMENINO))
                .isEqualTo(Sex.FEMENINO);
    }

    @Test
    void toDomainSexShouldReturnNullWhenSourceIsNull() {
        assertThat(PatientApiMapper.toDomainSex(null)).isNull();
    }

    @Test
    void toApiSexShouldMapCorrectly() {
        assertThat(PatientApiMapper.toApiSex(Sex.MASCULINO))
                .isEqualTo(PatientSex.MASCULINO);

        assertThat(PatientApiMapper.toApiSex(Sex.FEMENINO))
                .isEqualTo(PatientSex.FEMENINO);
    }

    @Test
    void toApiSexShouldReturnNullWhenSourceIsNull() {
        assertThat(PatientApiMapper.toApiSex(null)).isNull();
    }
}
