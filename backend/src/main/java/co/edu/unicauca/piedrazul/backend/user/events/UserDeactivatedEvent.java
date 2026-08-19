package co.edu.unicauca.piedrazul.backend.user.events;

public record UserDeactivatedEvent(
        String userId,
        String performedBy,
        String performedByRole,
        String correlationId,
        String rolesBefore,
        String rolesAfter
) implements UserRoleAuditEvent {

    public static UserDeactivatedEvent of(String userId, String performedBy, String performedByRole,
                                          String correlationId, String rolesBefore, String rolesAfter) {
        return new UserDeactivatedEvent(userId, performedBy, performedByRole, correlationId, rolesBefore, rolesAfter);
    }
}
