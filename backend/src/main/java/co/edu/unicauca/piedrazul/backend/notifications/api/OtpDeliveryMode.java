package co.edu.unicauca.piedrazul.backend.notifications.api;

/**
 * Capacidad actual para hacer llegar un código de verificación. Se determina con los proveedores
 * registrados al arrancar; cualquier combinación desconocida o incoherente es {@link #UNAVAILABLE}.
 */
public enum OtpDeliveryMode {

    /** No hay un canal apto: no se deben emitir códigos. */
    UNAVAILABLE,

    /**
     * Sin proveedores reales y con el opt-in de diagnóstico por consola. Solo para entornos con
     * datos exclusivamente sintéticos; la salida por consola no es entrega al destinatario.
     */
    DIAGNOSTIC_CONSOLE,

    /** Un proveedor de correo apto para códigos está registrado. */
    EMAIL
}
