package co.edu.unicauca.piedrazul.backend.user.events;

public sealed interface UserRoleAuditEvent permits UserActivatedEvent, UserDeactivatedEvent {
    String userId();
    String performedBy();
    String performedByRole();
    String correlationId();
    String rolesBefore();
    String rolesAfter();
}