package co.edu.unicauca.piedrazul.backend.doctors.events;

/**
 * Se creó, eliminó o recortó un periodo de descanso de un doctor. Es para la auditoría.
 *
 * <p>{@code doctorId} es el objeto sobre el que se actuó, así el historial de un doctor se
 * consulta por su id. Quien actuó ({@code performedBy}) es la cuenta que hizo la petición.
 * Los estados son JSON con el rango ({@code startDate}, {@code endDate}); no incluyen el
 * motivo porque es texto libre. {@code beforeState} es null al crear y {@code afterState}
 * es null al eliminar.
 */
public record TimeOffChangedEvent(
        String doctorId,
        Change change,
        String performedBy,
        String performedByRole,
        String correlationId,
        String beforeState,
        String afterState
) {
    public enum Change {
        CREATED,
        DELETED,
        TRUNCATED
    }
}
