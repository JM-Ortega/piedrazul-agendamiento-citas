package co.edu.unicauca.piedrazul.backend.user.events;

public record UserCreatedEvent(
        String userId,
        String createdBy,
        String creatorRole,
        String correlationId
) {
    public static UserCreatedEvent of(String userId, String createdBy, String creatorRole, String correlationId) {
        return new UserCreatedEvent(
                userId,
                createdBy,
                creatorRole,
                correlationId
        );
    }
}
