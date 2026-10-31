package co.edu.unicauca.piedrazul.backend.patients.domain;

import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;

import java.time.LocalDate;
import java.time.Period;

/**
 * Encapsula las reglas de coherencia necesarias para registrar un {@link Patient}.
 *
 * <p>La misma política se usa como prevalidación antes de producir efectos externos
 * y como defensa durante la construcción final del paciente.
 *
 * <p>La coherencia entre documento y edad requiere el tipo de documento, que
 * pertenece a la persona y no al paciente.
 */
public final class PatientRegistrationPolicy {

    private static final int LEGAL_AGE = 18;

    private PatientRegistrationPolicy() {
    }

    /**
     * Valida las reglas de coherencia de edad, documento y teléfono familiar.
     *
     * <p>No valida la obligatoriedad de los datos por sí sola: cuando faltan datos
     * necesarios, esa responsabilidad corresponde al contrato que invoca la
     * política.
     */
    public static void validate(
            IdentificationType identificationType,
            LocalDate birthDate,
            String guardianPhone
    ) {
        if (birthDate == null) {
            return;
        }

        LocalDate today = LocalDate.now();

        if (birthDate.isAfter(today)) {
            throw new InvalidPatientDataException("La fecha de nacimiento no puede ser futura");
        }

        boolean minor = isMinor(birthDate, today);

        if (minor && isBlank(guardianPhone)) {
            throw new InvalidPatientDataException(
                    "El teléfono de familiar es obligatorio para menores de edad"
            );
        }

        if (identificationType == null) {
            return;
        }

        if (minor && identificationType == IdentificationType.CEDULA) {
            throw new InvalidPatientDataException("Un menor de edad no puede tener cédula");
        }

        if (!minor && (identificationType == IdentificationType.TARJETA_IDENTIDAD
                || identificationType == IdentificationType.REGISTRO_NACIMIENTO)) {
            throw new InvalidPatientDataException(
                    "El tipo de documento no corresponde a una persona mayor de edad"
            );
        }
    }

    private static boolean isMinor(LocalDate birthDate, LocalDate reference) {
        return Period.between(birthDate, reference).getYears() < LEGAL_AGE;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
