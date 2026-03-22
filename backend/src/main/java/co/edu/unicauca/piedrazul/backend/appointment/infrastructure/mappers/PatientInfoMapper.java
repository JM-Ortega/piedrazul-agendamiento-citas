package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;


import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;

public final class PatientInfoMapper {

    private PatientInfoMapper() {
    }

    public static PatientInfo toPatientInfo(PatientData source) {
        if (source == null) {
            throw new IllegalArgumentException("PatientData cannot be null");
        }

        return PatientInfo.of(
                mapDocumentType(source.documentType()),
                source.documentNumber(),
                source.firstName(),
                source.lastName(),
                source.phone(),
                mapGender(source.gender()),
                source.birthDate(),
                source.email(),
                source.guardianPhone()
        );
    }

    private static DocumentType mapDocumentType(
            co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType source
    ) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case CEDULA -> DocumentType.CEDULA;
            case TARJETA_IDENTIDAD -> DocumentType.TARJETA_IDENTIDAD;
            case REGISTRO_NACIMIENTO -> DocumentType.REGISTRO_NACIMIENTO;
            case PASAPORTE -> DocumentType.PASAPORTE;
        };
    }

    private static Gender mapGender(
            co.edu.unicauca.piedrazul.backend.patients.domain.Gender source
    ) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case MALE -> Gender.MALE;
            case FEMALE -> Gender.FEMALE;
            case OTHER -> Gender.OTHER;
        };
    }
}