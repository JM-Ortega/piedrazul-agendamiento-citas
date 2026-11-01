package co.edu.unicauca.piedrazul.backend.notifications.domain.port.output;

/**
 * Resultado de entregar un mensaje a un proveedor, desde el punto de vista del despachador. No se
 * persiste: el intento guarda su traducción a {@code AttemptStatus} y {@code FailureType}.
 *
 * <ul>
 *   <li>{@link #ACCEPTED}: el proveedor aceptó procesar el mensaje. No prueba que llegara al buzón.</li>
 *   <li>{@link #RETRYABLE}: hay evidencia positiva de no aceptación transitoria (incluido el circuit
 *       breaker abierto, que rechaza antes de invocar al proveedor).</li>
 *   <li>{@link #REJECTED}: rechazo permanente del destinatario o del contenido.</li>
 *   <li>{@link #PROVIDER_ERROR}: no aceptación probada que requiere intervención (configuración,
 *       credenciales, proveedor solo de diagnóstico).</li>
 *   <li>{@link #UNKNOWN}: no se puede descartar que el proveedor lo aceptara.</li>
 * </ul>
 *
 * Ni {@link #ACCEPTED} ni {@link #UNKNOWN} admiten reintento ni cambio de canal.
 */
public enum DeliveryOutcome {
    ACCEPTED,
    RETRYABLE,
    REJECTED,
    PROVIDER_ERROR,
    UNKNOWN
}
