package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;

public final class PatientRegistrationMapper {

    private PatientRegistrationMapper() {
    }

    public static PatientDocumentType mapDocumentType(DocumentType source) {
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

    public static PatientGender mapGender(Gender source) {
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