package co.edu.unicauca.piedrazul.backend.patients.events;

/**
 * Evento publicado cuando se modifican los datos de un paciente. Es para la
 * auditoría.
 *
 * <p>{@code beforeState} y {@code afterState} son JSON con solo los campos que
 * cambiaron. La bitácora es append-only, así que no se guardan datos personales
 * en claro: el número de documento va enmascarado y los demás datos personales
 * solo dejan constancia de que cambiaron.
 */
public record PatientUpdatedEvent(
        String patientId,
        String performedBy,
        String performedByRole,
        String correlationId,
        String beforeState,
        String afterState
) {
}
