package co.edu.unicauca.piedrazul.backend.notifications.application.exception;

/**
 * Error cargando templates desde infraestructura
 * (classpath, archivos, recursos, IO, etc.).
 */
public class TemplateLoadingException extends NotificationDispatchException {

    public TemplateLoadingException(String message) {
        super("TEMPLATE_LOADING_ERROR", message);
    }

    public TemplateLoadingException(
            String message,
            Throwable cause
    ) {
        super(
                "TEMPLATE_LOADING_ERROR",
                message,
                cause
        );
    }
}