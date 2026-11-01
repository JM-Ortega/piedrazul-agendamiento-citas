package co.edu.unicauca.piedrazul.backend.patients.api.dto.output;

import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;

/**
 * Estado público de un documento frente al registro y la habilitación de acceso.
 *
 * <ul>
 *   <li>{@code patientExists}: existe {@code Patient}.</li>
 *   <li>{@code hasUserAccount}: {@code Person} tiene una cuenta vinculada.</li>
 *   <li>{@code hasSystemUser}: existe una cuenta para el documento en el
 *       proveedor de identidad, aunque no esté vinculada a {@code Person}.</li>
 *   <li>{@code hasPatientRole}: la cuenta vinculada posee el rol de paciente;
 *       si no hay cuenta vinculada, es {@code false}.</li>
 * </ul>
 */
public record PatientPublicResponse(
        String identificationType,
        String maskedDocument,
        String firstName,
        String lastName,
        boolean patientExists,
        boolean hasUserAccount,
        boolean hasSystemUser,
        boolean hasPatientRole
) {

    public static PatientPublicResponse from(PersonSummary person, boolean hasSystemUser, boolean hasPatientRole) {
        return new PatientPublicResponse(
                person.identificationType().toString(),
                maskDocument(person.identification()),
                person.firstName(),
                person.lastName(),
                true,
                person.userId() != null,
                hasSystemUser,
                hasPatientRole
        );
    }

    public static PatientPublicResponse fromPersonWithoutPatient(
            PersonSummary person, boolean hasSystemUser, boolean hasPatientRole) {
        return new PatientPublicResponse(
                person.identificationType().toString(),
                maskDocument(person.identification()),
                person.firstName(),
                person.lastName(),
                false,
                person.userId() != null,
                hasSystemUser,
                hasPatientRole
        );
    }

    public static PatientPublicResponse fromSystemUserOnly(String documentNumber) {
        return new PatientPublicResponse(
                null,
                maskDocument(documentNumber),
                null,
                null,
                false,
                false,
                true,
                false
        );
    }

    private static String maskDocument(String documentNumber) {
        if (documentNumber.length() <= 4) {
            return "****";
        }
        return "*".repeat(documentNumber.length() - 4)
                + documentNumber.substring(documentNumber.length() - 4);
    }
}
