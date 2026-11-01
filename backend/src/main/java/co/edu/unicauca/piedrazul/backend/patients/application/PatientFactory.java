package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.PatientRegistrationPolicy;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.mappers.PatientApiMapper;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;

import java.time.LocalDate;

/**
 * Centraliza la construcción de {@link Patient} aplicando
 * {@link PatientRegistrationPolicy}.
 */
final class PatientFactory {

    private PatientFactory() {
    }

    static Patient create(
            PersonSummary person,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    ) {
        PatientRegistrationPolicy.validate(person.identificationType(), birthDate, guardianPhone);

        return new Patient(
                person.id(),
                PatientApiMapper.toDomainSex(sex),
                birthDate,
                guardianPhone
        );
    }
}
