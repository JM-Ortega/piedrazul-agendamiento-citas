package co.edu.unicauca.piedrazul.backend.user.infrastructure;

import java.util.UUID;

/**
 * Resultado clasificado de una solicitud de creación de cuenta en Keycloak. Distingue lo
 * que se sabe con certeza de lo que no: solo {@link Created} prueba que la cuenta la creó
 * esta solicitud (Keycloak devolvió su id), y solo {@link Conflict} y {@link Rejected}
 * prueban que no se creó nada. {@link Uncertain} no permite suponer ninguna de las dos.
 */
public sealed interface AccountCreationResult {

    /** 201 con el id de la cuenta creada por esta solicitud. */
    record Created(UUID userId) implements AccountCreationResult {
    }

    /** 409: ya existe una cuenta con ese username o correo; esta solicitud no creó nada. */
    record Conflict() implements AccountCreationResult {
    }

    /** 4xx distinto de 409: Keycloak rechazó la solicitud sin crear nada. */
    record Rejected(int status) implements AccountCreationResult {
    }

    /**
     * Sin respuesta concluyente (timeout, error de transporte, 5xx, 201 sin id legible):
     * la cuenta pudo crearse o no. {@code cause} es un código o el nombre de la excepción,
     * nunca un mensaje con datos.
     */
    record Uncertain(String cause) implements AccountCreationResult {
    }
}
