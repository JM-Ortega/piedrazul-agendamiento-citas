package co.edu.unicauca.piedrazul.backend.jackson.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.BeanWrapperImpl;

import java.util.Locale;

public class ValidDocumentValidator implements ConstraintValidator<ValidDocument, Object> {

    private static final String UNKNOWN_TYPE_MESSAGE = "El tipo de documento no es válido";
    private static final String DOCUMENT_REQUIRED_MESSAGE = "El número de documento es obligatorio cuando se envía el tipo de documento";
    private static final String TYPE_REQUIRED_MESSAGE = "El tipo de documento es obligatorio cuando se envía el número de documento";

    private String documentField;
    private String typeField;

    @Override
    public void initialize(ValidDocument annotation) {
        this.documentField = annotation.documentField();
        this.typeField = annotation.typeField();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        BeanWrapperImpl bean = new BeanWrapperImpl(value);
        String identification = normalizeValue(bean.getPropertyValue(documentField));
        String identificationType = normalizeType(bean.getPropertyValue(typeField));

        if (identification == null && identificationType == null) {
            return true;
        }

        if (identification == null) {
            return addViolation(context, documentField, DOCUMENT_REQUIRED_MESSAGE);
        }

        if (identificationType == null) {
            return addViolation(context, typeField, TYPE_REQUIRED_MESSAGE);
        }

        ValidationResult validationResult = validateDocument(identification, identificationType);

        if (!validationResult.valid()) {
            return addViolation(context, validationResult.field(), validationResult.message());
        }

        return true;
    }

    private String normalizeType(Object typeRaw) {
        if (typeRaw == null) {
            return null;
        }

        if (typeRaw instanceof Enum<?> enumValue) {
            return enumValue.name().trim().toUpperCase(Locale.ROOT);
        }

        String value = typeRaw.toString().trim();
        return value.isEmpty() ? null : value.toUpperCase(Locale.ROOT);
    }

    private String normalizeValue(Object rawValue) {
        if (rawValue == null) {
            return null;
        }

        String value = rawValue.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private ValidationResult validateDocument(String documentNumber, String documentType) {
        return switch (documentType) {
            case "CEDULA" -> new ValidationResult(documentNumber.matches("^[0-9]{6,10}$"), documentField,
                    "La cédula debe contener entre 6 y 10 dígitos numéricos");

            case "TARJETA_IDENTIDAD" -> new ValidationResult(documentNumber.matches("^[0-9]{10,11}$"), documentField,
                    "La tarjeta de identidad debe contener entre 10 y 11 dígitos numéricos");

            case "REGISTRO_NACIMIENTO" -> new ValidationResult(documentNumber.matches("^[0-9]{8,20}$"), documentField,
                    "El registro de nacimiento debe contener entre 8 y 20 dígitos numéricos");

            case "PASAPORTE" -> new ValidationResult(documentNumber.matches("^[A-Za-z0-9]{6,9}$"), documentField,
                    "El pasaporte debe contener entre 6 y 9 caracteres alfanuméricos");

            default -> new ValidationResult(false, typeField, UNKNOWN_TYPE_MESSAGE + ": " + documentType);
        };
    }

    private boolean addViolation(ConstraintValidatorContext context, String field, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(field)
                .addConstraintViolation();
        return false;
    }
}
