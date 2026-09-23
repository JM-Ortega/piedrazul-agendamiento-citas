package co.edu.unicauca.piedrazul.backend.jackson.sanitization;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * permite únicamente etiquetas HTML muy básicas y seguras (como <b>, <i>, <a>, <strong>), pero elimina cualquier
 * código peligroso como scripts (<script>), atributos maliciosos (onclick), u hojas de estilo que
 * puedan alterar la aplicación. Se usa para evitar ataques de seguridad como XSS (Cross-Site Scripting)
 */

@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@JacksonAnnotationsInside
@JsonDeserialize(using = SanitizeDeserializer.class)
public @interface Sanitize {
}
