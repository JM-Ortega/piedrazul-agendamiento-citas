package co.edu.unicauca.piedrazul.backend.jackson.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.BeanWrapperImpl;

public class ValidDocumentValidator implements ConstraintValidator<ValidDocument, Object> {

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
        Object documentRaw = bean.getPropertyValue(documentField);
        Object typeRaw = bean.getPropertyValue(typeField);

        if (!(documentRaw instanceof String documentNumber) || typeRaw == null) {
            return true;
        }

        if (documentNumber.isBlank()) {
            return true;
        }

        String documentType = resolveTypeName(typeRaw);
        boolean valid = isValidDocument(documentNumber, documentType);

        if (!valid) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode(documentField)
                    .addConstraintViolation();
        }

        return valid;
    }

    private String resolveTypeName(Object typeRaw) {
        if (typeRaw instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return typeRaw.toString();
    }

    private boolean isValidDocument(String documentNumber, String documentType) {
        return switch (documentType) {
            case "CEDULA" -> documentNumber.matches("^[0-9]{6,10}$");
            case "TARJETA_IDENTIDAD" -> documentNumber.matches("^[0-9]{10,11}$");
            case "REGISTRO_NACIMIENTO" -> documentNumber.matches("^[0-9]{8,20}$");
            case "PASAPORTE" -> documentNumber.matches("^[A-Za-z0-9]{6,9}$");
            default -> true;
        };
    }
}
