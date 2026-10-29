package co.edu.unicauca.piedrazul.backend.verification.exception;

import org.springframework.http.HttpStatus;

/**
 * Los contactos registrados no permiten hacer llegar un código por el canal disponible. El
 * mensaje no revela el contacto ni el motivo concreto.
 */
public class VerificationContactUnusableException extends VerificationBusinessException {

    public VerificationContactUnusableException() {
        super(
                "No es posible completar la solicitud con los datos de contacto registrados. "
                        + "Comunícate con la clínica para verificar o actualizar tu información.",
                "VERIFICATION_CONTACT_UNUSABLE",
                HttpStatus.CONFLICT
        );
    }
}
