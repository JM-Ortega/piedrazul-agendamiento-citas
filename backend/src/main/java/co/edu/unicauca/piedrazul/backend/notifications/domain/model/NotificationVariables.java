package co.edu.unicauca.piedrazul.backend.notifications.domain.model;

/** Nombres de variables de template con significado para el despacho, no solo para el texto. */
public final class NotificationVariables {

    /** Código de un solo uso. Es secreto: se elimina al terminar el despacho. */
    public static final String CODE = "code";

    /**
     * Instante ISO-8601 a partir del cual el contenido ya no sirve (vencimiento del código). No es
     * secreto; el despacho no entrega nada al proveedor a partir de ese instante.
     */
    public static final String EXPIRES_AT = "expiresAt";

    /**
     * Veces que la recuperación reencoló este trabajo sin que se hubiera enviado nada. No es
     * secreto; no forma parte de ninguna plantilla.
     */
    public static final String DISPATCH_RECOVERIES = "dispatchRecoveries";

    private NotificationVariables() {
    }
}
