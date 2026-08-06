package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;

public final class PatientRegistrationMapper {

    private PatientRegistrationMapper() {
    }

    public static IdentificationType mapDocumentType(DocumentType source) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case CEDULA -> IdentificationType.CEDULA;
            case TARJETA_IDENTIDAD -> IdentificationType.TARJETA_IDENTIDAD;
            case REGISTRO_NACIMIENTO -> IdentificationType.REGISTRO_NACIMIENTO;
            case PASAPORTE -> IdentificationType.PASAPORTE;
        };
    }

    public static PatientSex mapGender(Gender source) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case MASCULINO -> PatientSex.MASCULINO;
            case FEMENINO -> PatientSex.FEMENINO;
            case OTRO -> throw new IllegalArgumentException(
                    "El valor de género OTRO ya no es soportado por el modelo de paciente"
            );
        };
    }
}
