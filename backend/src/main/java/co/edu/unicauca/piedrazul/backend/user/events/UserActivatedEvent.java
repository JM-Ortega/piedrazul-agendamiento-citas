package co.edu.unicauca.piedrazul.backend.user.events;

public record UserActivatedEvent(
        String userId,
        String performedBy,
        String performedByRole,
        String correlationId,
        String rolesBefore,
        String rolesAfter
) implements UserRoleAuditEvent {

    public static UserActivatedEvent of(
            String userId,
            String performedBy,
            String performedByRole,
            String correlationId,
            String rolesBefore,
            String rolesAfter) {
        return new UserActivatedEvent(userId, performedBy, performedByRole, correlationId, rolesBefore, rolesAfter);
    }
}
