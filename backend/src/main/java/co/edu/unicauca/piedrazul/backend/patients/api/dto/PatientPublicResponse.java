package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;

public record PatientPublicResponse(
        String documentType,
        String maskedDocument,
        String firstName,
        String lastName,
        boolean patientExists,
        boolean hasUserAccount,
        boolean hasSystemUser
) {

    // Caso: existe paciente en dominio
    public static PatientPublicResponse from(Patient patient, boolean hasSystemUser) {
        return new PatientPublicResponse(
                patient.getDocumentType().toString(),
                maskDocument(patient.getDocumentNumber()),
                patient.getFirstName(),
                patient.getLastName(),
                true,
                patient.hasUserAccount(),
                hasSystemUser
        );
    }

    // Caso: NO existe paciente pero sí existe usuario del sistema
    public static PatientPublicResponse fromSystemUserOnly(String documentNumber) {
        return new PatientPublicResponse(
                null,
                maskDocument(documentNumber),
                null,
                null,
                false,
                false,
                true
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