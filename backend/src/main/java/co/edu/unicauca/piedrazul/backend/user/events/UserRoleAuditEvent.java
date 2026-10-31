package co.edu.unicauca.piedrazul.backend.user.events;

/**
 * Cambio en los roles de una cuenta: asignación o revocación. Activar o desactivar la
 * cuenta es otra cosa, ver {@link UserAccountStatusChangedEvent}.
 */
public sealed interface UserRoleAuditEvent permits UserRoleAssignedEvent, UserRoleRevokedEvent {
    String userId();
    String performedBy();
    String performedByRole();
    String correlationId();
    String rolesBefore();
    String rolesAfter();
}
