package co.edu.unicauca.piedrazul.backend.patients.infrastructure.mappers;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;

public final class PatientApiMapper {

    private PatientApiMapper() {
    }

    public static PatientData toPatientData(Patient patient, PersonSummary person) {
        if (patient == null) {
            throw new IllegalArgumentException("Patient cannot be null");
        }
        if (person == null) {
            throw new IllegalArgumentException("PersonSummary cannot be null");
        }

        return new PatientData(
                patient.getPersonId(),
                person.userId(),
                person.identificationType(),
                person.identification(),
                person.firstName(),
                person.lastName(),
                person.phone(),
                person.email(),
                toApiSex(patient.getSex()),
                patient.getBirthDate(),
                patient.getGuardianPhone()
        );
    }

    public static Sex toDomainSex(PatientSex source) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case MASCULINO -> Sex.MASCULINO;
            case FEMENINO -> Sex.FEMENINO;
            case OTRO -> Sex.OTRO;
        };
    }

    public static PatientSex toApiSex(Sex source) {
        if (source == null) {
            return null;
        }

        return switch (source) {
            case MASCULINO -> PatientSex.MASCULINO;
            case FEMENINO -> PatientSex.FEMENINO;
            case OTRO -> PatientSex.OTRO;
        };
    }
}
