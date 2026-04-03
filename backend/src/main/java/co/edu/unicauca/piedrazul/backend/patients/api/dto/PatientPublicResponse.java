package co.edu.unicauca.piedrazul.backend.patients.api.dto;

import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;

public record PatientPublicResponse(
        String documentType,
        String maskedDocument,
        String firstName,
        String lastName,
        boolean hasUserAccount
) {
    public static PatientPublicResponse from(Patient patient) {
        return new PatientPublicResponse(
                patient.getDocumentType().toString(),
                maskDocument(patient.getDocumentNumber()),
                patient.getFirstName(),
                patient.getLastName(),
                patient.hasUserAccount()
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