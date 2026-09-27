package co.edu.unicauca.piedrazul.backend.audit.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Un registro de auditoría. {@code actorName} y {@code actorUsername} son los datos vigentes de
 * la persona dueña de la cuenta {@code actorId}; son nulos si la cuenta no tiene persona
 * asociada.
 */
@Schema(description = "Registro de auditoría")
public record AuditEventResponse(
        @Schema(description = "Identificador del registro de auditoría",
                example = "6f1c2a34-5b7d-4e89-a0c1-2d3e4f5a6b7c")
        UUID id,

        @Schema(description = "Fecha y hora en que ocurrió la acción (UTC)", example = "2026-09-26T17:42:10.512Z")
        Instant timestamp,

        @Schema(description = "Nombre completo, vigente, de quien ejecutó la acción. Nulo si la cuenta no tiene "
                + "persona asociada", example = "Ana Ruiz", nullable = true)
        String actorName,

        @Schema(description = "Usuario de Keycloak de quien ejecutó la acción, que es el número de documento de su "
                + "persona. Nulo si la cuenta no tiene persona asociada", example = "1002003004", nullable = true)
        String actorUsername,

        @Schema(description = "Id de la cuenta de Keycloak de quien ejecutó la acción. En un flujo público, como el "
                + "registro de un paciente, es la propia cuenta afectada. Es `system` cuando la acción la hace el "
                + "sistema, por ejemplo al arrancar la aplicación", example = "3f2c1d0e-8a41-4b6e-9d57-2b1e6f0a7c11")
        String actorId,

        @Schema(description = "Roles que tenía quien ejecutó la acción en ese momento", example = "[\"DOCTOR\"]")
        List<String> actorRoles,

        @Schema(description = "Acción realizada", example = "PACIENTE_MODIFICADO")
        String action,

        @Schema(description = "Resultado de la acción", example = "DENEGADO",
                allowableValues = {"EXITOSO", "FALLIDO", "DENEGADO"})
        String outcome,

        @Schema(description = "Tipo del objeto afectado", example = "Paciente", nullable = true)
        String targetEntityType,

        @Schema(description = "Id del objeto afectado. `N/A` cuando la acción no tiene un objeto identificable",
                example = "9b8f2d64-1c3e-4f0a-a7d5-5e6b8c9d0e12", nullable = true)
        String targetEntityId,

        @Schema(description = "Identificador de correlación de la petición, para rastrearla en los registros",
                example = "b1946ac9-2f3d-4c1e-8a7b-9c0d1e2f3a4b", nullable = true)
        String correlationId
) { }
