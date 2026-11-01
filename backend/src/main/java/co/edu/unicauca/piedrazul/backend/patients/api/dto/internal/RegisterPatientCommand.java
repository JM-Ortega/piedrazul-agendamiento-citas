package co.edu.unicauca.piedrazul.backend.patients.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;

import java.time.LocalDate;

/**
 * Contiene los datos necesarios para resolver o registrar un paciente desde otro
 * módulo.
 */
public record RegisterPatientCommand(
        IdentificationType identificationType,
        String documentNumber,
        String firstName,
        String lastName,
        String phone,
        String email,
        PatientSex sex,
        LocalDate birthDate,
        String guardianPhone
) {
}
