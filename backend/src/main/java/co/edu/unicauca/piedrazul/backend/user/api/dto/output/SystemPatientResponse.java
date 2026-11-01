package co.edu.unicauca.piedrazul.backend.user.api.dto.output;

import java.util.UUID;

/**
 * Un paciente que tiene cuenta de usuario. {@code id} es el id del paciente (el de su
 * persona), el mismo que reciben {@code PUT /api/user/patients/{id}/activate} y
 * {@code /deactivate}.
 */
public record SystemPatientResponse(
        UUID id,
        String firstName,
        String lastName,
        String documentId,
        boolean accountEnabled
) {
}
