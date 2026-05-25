package co.edu.unicauca.piedrazul.backend.jackson.normalization;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@JsonDeserialize(using = NameSanitizerDeserializer.class)
public @interface NormalizeName {
}
