package co.edu.unicauca.piedrazul.backend.patients.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.domain.Gender;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;

public final class PatientApiMapper {

    private PatientApiMapper() {
    }

    public static PatientData toPatientData(Patient source) {
        if (source == null) {
            throw new IllegalArgumentException("Patient cannot be null");
        }

        return new PatientData(
                source.getId(),
                source.getUserId(),
                toApiDocumentType(source.getDocumentType()),
                source.getDocumentNumber(),
                source.getFirstName(),
                source.getLastName(),
                source.getPhone(),
                source.getEmail(),
                toApiGender(source.getGender()),
                source.getBirthDate(),
                source.getGuardianPhone()
        );
    }

    public static DocumentType toDomainDocumentType(PatientDocumentType source) {
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

    public static Gender toDomainGender(PatientGender source) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case MASCULINO -> Gender.MASCULINO;
            case FEMENINO -> Gender.FEMENINO;
            case OTRO -> Gender.OTRO;
        };
    }

    public static PatientDocumentType toApiDocumentType(DocumentType source) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case CEDULA -> PatientDocumentType.CEDULA;
            case TARJETA_IDENTIDAD -> PatientDocumentType.TARJETA_IDENTIDAD;
            case REGISTRO_NACIMIENTO -> PatientDocumentType.REGISTRO_NACIMIENTO;
            case PASAPORTE -> PatientDocumentType.PASAPORTE;
        };
    }

    public static PatientGender toApiGender(Gender source) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case MASCULINO -> PatientGender.MASCULINO;
            case FEMENINO -> PatientGender.FEMENINO;
            case OTRO -> PatientGender.OTRO;
        };
    }
}