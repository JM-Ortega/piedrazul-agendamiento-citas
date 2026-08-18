package co.edu.unicauca.piedrazul.backend.clinicalHistory.events;

import java.util.UUID;

public record ClinicalHistoryCreatedEvent (
        UUID clinicalHistoryId,
        String username,
        String rol,
        String correlationId
){
    public static ClinicalHistoryCreatedEvent of(UUID clinicalHistoryId, String username, String rol, String correlationId) {
        return new ClinicalHistoryCreatedEvent(
                clinicalHistoryId,
                username,
                rol,
                correlationId
        );
    }
}
