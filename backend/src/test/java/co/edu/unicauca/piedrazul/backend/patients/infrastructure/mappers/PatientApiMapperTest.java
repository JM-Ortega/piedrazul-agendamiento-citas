package co.edu.unicauca.piedrazul.backend.patients.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.domain.Gender;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class PatientApiMapperTest {

    @Test
    void toPatientDataShouldMapAllFieldsCorrectly() {
        UUID userId = UUID.randomUUID();

        Patient patient = new Patient(
                DocumentType.CEDULA,
                "123",
                "Juan",
                "Perez",
                "300",
                "mail@test.com",
                Gender.MASCULINO,
                LocalDate.of(2000, 1, 1),
                "111",
                userId
        );

        PatientData result = PatientApiMapper.toPatientData(patient);

        assertThat(result).isNotNull();
        assertThat(result.id()).isNull(); // JPA no asigna id en test
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.documentType()).isEqualTo(PatientDocumentType.CEDULA);
        assertThat(result.documentNumber()).isEqualTo("123");
        assertThat(result.firstName()).isEqualTo("Juan");
        assertThat(result.lastName()).isEqualTo("Perez");
        assertThat(result.phone()).isEqualTo("300");
        assertThat(result.email()).isEqualTo("mail@test.com");
        assertThat(result.gender()).isEqualTo(PatientGender.MASCULINO);
        assertThat(result.birthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(result.guardianPhone()).isEqualTo("111");
    }

    @Test
    void toPatientDataShouldThrowExceptionWhenSourceIsNull() {
        assertThatThrownBy(() -> PatientApiMapper.toPatientData(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Patient cannot be null");
    }

    // =========================
    // DocumentType mappings
    // =========================

    @Test
    void toDomainDocumentTypeShouldMapCorrectly() {
        assertThat(PatientApiMapper.toDomainDocumentType(PatientDocumentType.CEDULA))
                .isEqualTo(DocumentType.CEDULA);

        assertThat(PatientApiMapper.toDomainDocumentType(PatientDocumentType.TARJETA_IDENTIDAD))
                .isEqualTo(DocumentType.TARJETA_IDENTIDAD);

        assertThat(PatientApiMapper.toDomainDocumentType(PatientDocumentType.REGISTRO_NACIMIENTO))
                .isEqualTo(DocumentType.REGISTRO_NACIMIENTO);

        assertThat(PatientApiMapper.toDomainDocumentType(PatientDocumentType.PASAPORTE))
                .isEqualTo(DocumentType.PASAPORTE);
    }

    @Test
    void toDomainDocumentTypeShouldReturnNullWhenSourceIsNull() {
        assertThat(PatientApiMapper.toDomainDocumentType(null)).isNull();
    }

    @Test
    void toApiDocumentTypeShouldMapCorrectly() {
        assertThat(PatientApiMapper.toApiDocumentType(DocumentType.CEDULA))
                .isEqualTo(PatientDocumentType.CEDULA);

        assertThat(PatientApiMapper.toApiDocumentType(DocumentType.TARJETA_IDENTIDAD))
                .isEqualTo(PatientDocumentType.TARJETA_IDENTIDAD);

        assertThat(PatientApiMapper.toApiDocumentType(DocumentType.REGISTRO_NACIMIENTO))
                .isEqualTo(PatientDocumentType.REGISTRO_NACIMIENTO);

        assertThat(PatientApiMapper.toApiDocumentType(DocumentType.PASAPORTE))
                .isEqualTo(PatientDocumentType.PASAPORTE);
    }

    @Test
    void toApiDocumentTypeShouldReturnNullWhenSourceIsNull() {
        assertThat(PatientApiMapper.toApiDocumentType(null)).isNull();
    }

    // =========================
    // Gender mappings
    // =========================

    @Test
    void toDomainGenderShouldMapCorrectly() {
        assertThat(PatientApiMapper.toDomainGender(PatientGender.MASCULINO))
                .isEqualTo(Gender.MASCULINO);

        assertThat(PatientApiMapper.toDomainGender(PatientGender.FEMENINO))
                .isEqualTo(Gender.FEMENINO);

        assertThat(PatientApiMapper.toDomainGender(PatientGender.OTRO))
                .isEqualTo(Gender.OTRO);
    }

    @Test
    void toDomainGenderShouldReturnNullWhenSourceIsNull() {
        assertThat(PatientApiMapper.toDomainGender(null)).isNull();
    }

    @Test
    void toApiGenderShouldMapCorrectly() {
        assertThat(PatientApiMapper.toApiGender(Gender.MASCULINO))
                .isEqualTo(PatientGender.MASCULINO);

        assertThat(PatientApiMapper.toApiGender(Gender.FEMENINO))
                .isEqualTo(PatientGender.FEMENINO);

        assertThat(PatientApiMapper.toApiGender(Gender.OTRO))
                .isEqualTo(PatientGender.OTRO);
    }

    @Test
    void toApiGenderShouldReturnNullWhenSourceIsNull() {
        assertThat(PatientApiMapper.toApiGender(null)).isNull();
    }
}