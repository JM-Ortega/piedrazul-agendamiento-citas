package co.edu.unicauca.piedrazul.backend.medicalCheckup.events;

import java.util.UUID;

public record MedicalCheckupCreatedEvent(
        UUID medicalCheckupId,
        String username,
        String rol,
        String correlationId
){
    public static MedicalCheckupCreatedEvent of(UUID medicalCheckupId, String username, String rol, String correlationId) {
        return new MedicalCheckupCreatedEvent(
                medicalCheckupId,
                username,
                rol,
                correlationId
        );
    }
}
