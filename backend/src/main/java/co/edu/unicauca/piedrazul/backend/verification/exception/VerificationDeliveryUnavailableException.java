package co.edu.unicauca.piedrazul.backend.verification.exception;

import org.springframework.http.HttpStatus;

/**
 * No hay un canal apto para hacer llegar códigos. Es una condición de configuración esperada
 * (p. ej. mientras no haya proveedor de correo), no un fallo inesperado.
 */
public class VerificationDeliveryUnavailableException extends VerificationBusinessException {

    public VerificationDeliveryUnavailableException() {
        super(
                "El servicio de envío de códigos no está disponible temporalmente. Inténtalo más tarde.",
                "VERIFICATION_DELIVERY_UNAVAILABLE",
                HttpStatus.SERVICE_UNAVAILABLE
        );
    }
}
