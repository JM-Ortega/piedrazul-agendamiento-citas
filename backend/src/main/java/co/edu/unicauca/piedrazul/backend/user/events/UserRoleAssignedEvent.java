package co.edu.unicauca.piedrazul.backend.user.events;

public record UserRoleAssignedEvent(
        String userId,
        String performedBy,
        String performedByRole,
        String correlationId,
        String rolesBefore,
        String rolesAfter
) implements UserRoleAuditEvent {

    public static UserRoleAssignedEvent of(
            String userId,
            String performedBy,
            String performedByRole,
            String correlationId,
            String rolesBefore,
            String rolesAfter) {
        return new UserRoleAssignedEvent(userId, performedBy, performedByRole, correlationId, rolesBefore, rolesAfter);
    }
}
