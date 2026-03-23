package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.domain.Gender;

public final class PatientRegistrationMapper {

    private PatientRegistrationMapper() {
    }

    public static DocumentType mapDocumentType(
            co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType source
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

    public static Gender mapGender(
            co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender source
    ) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case MASCULINO -> Gender.MASCULINO;
            case FEMENINO -> Gender.FEMENINO;
            case OTRO -> Gender.OTRO;
        };
    }
}