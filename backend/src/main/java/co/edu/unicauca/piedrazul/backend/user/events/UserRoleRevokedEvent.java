package co.edu.unicauca.piedrazul.backend.user.events;

public record UserRoleRevokedEvent(
        String userId,
        String performedBy,
        String performedByRole,
        String correlationId,
        String rolesBefore,
        String rolesAfter
) implements UserRoleAuditEvent {

    public static UserRoleRevokedEvent of(String userId, String performedBy, String performedByRole,
                                          String correlationId, String rolesBefore, String rolesAfter) {
        return new UserRoleRevokedEvent(userId, performedBy, performedByRole, correlationId, rolesBefore, rolesAfter);
    }
}
