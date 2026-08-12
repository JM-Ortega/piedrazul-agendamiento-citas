package co.edu.unicauca.piedrazul.backend.audit.infrastructure.aop;

import co.edu.unicauca.piedrazul.backend.shared.enums.AuditAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Para lo repetitivo (accesos a historia clínica, CRUD de usuarios)
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
}