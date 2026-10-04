package co.edu.unicauca.piedrazul.backend.patients.api.dto.internal;

import java.util.UUID;

/**
 * Fila del listado de pacientes que tienen cuenta de usuario. No incluye el estado de la
 * cuenta (activada o desactivada): eso vive en el proveedor de identidad, no en este módulo.
 */
public record PatientAccountSummary(
        UUID personId,
        UUID userId,
        String identification,
        String firstName,
        String lastName
) {
}
