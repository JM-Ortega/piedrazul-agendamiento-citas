package co.edu.unicauca.piedrazul.backend.shared.audit;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Audita la ejecución de un método de un controlador. Vive en {@code shared} para que
 * cualquier módulo pueda usarla sin depender del módulo de auditoría.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    AuditAction action();
    String targetEntityType() default "";
    /**
     * Expresión SpEL para extraer el id del target a partir de los
     * parámetros del método. Ej: "#idPatient" o "#request.id".
     * Si se omite, no se registra targetEntityId.
     */
    String targetIdExpression() default "";

    /**
     * Si es {@code true}, solo se registra el rechazo por permisos ({@code DENEGADO}).
     * El éxito y los fallos internos no se registran aquí.
     *
     * <p>Sirve para las acciones cuyo éxito ya audita un evento de dominio: registrar
     * el éxito también aquí lo duplicaría.
     */
    boolean onlyDenied() default false;
}
