package co.edu.unicauca.piedrazul.backend.user.events;

/**
 * Se activó o se desactivó la cuenta de usuario de un paciente (el indicador
 * {@code enabled} del proveedor de identidad). Es para la auditoría.
 *
 * <p>{@code patientId} es el id de la persona del paciente, no el de la cuenta: la
 * auditoría identifica al objeto sobre el que se actuó. Quien actuó ({@code performedBy})
 * sigue siendo el id de la cuenta que hizo la petición.
 *
 * <p>No es un cambio de roles: eso lo cubre {@link UserRoleAuditEvent}.
 */
public record UserAccountStatusChangedEvent(
        String patientId,
        String performedBy,
        String performedByRole,
        String correlationId,
        boolean enabledBefore,
        boolean enabledAfter
) {
    public static UserAccountStatusChangedEvent of(
            String patientId,
            String performedBy,
            String performedByRole,
            String correlationId,
            boolean enabledBefore,
            boolean enabledAfter) {
        return new UserAccountStatusChangedEvent(
                patientId, performedBy, performedByRole, correlationId, enabledBefore, enabledAfter);
    }
}
