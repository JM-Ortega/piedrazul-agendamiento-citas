package co.edu.unicauca.piedrazul.backend.user.exception;

import org.springframework.http.HttpStatus;

public class IdentityProviderException extends UserBusinessException {

    public IdentityProviderException(String message) {
        super("Error del proveedor de identidad: "+ message, "KEYCLOAK_EXCEPTION", HttpStatus.BAD_GATEWAY);
    }
}