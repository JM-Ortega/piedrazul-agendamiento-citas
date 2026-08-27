package co.edu.unicauca.piedrazul.backend.jackson.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidDocumentValidator.class)
public @interface ValidDocument {

    String message() default "El número de documento no coincide con el tipo de documento";

    String documentField() default "identification";

    String typeField() default "identificationType";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
