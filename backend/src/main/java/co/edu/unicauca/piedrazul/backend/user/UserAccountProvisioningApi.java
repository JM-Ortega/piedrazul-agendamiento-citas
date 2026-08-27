package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;

import java.util.List;

public interface UserAccountProvisioningApi {

    /**
     * Obtiene la cuenta existente o la crea, y garantiza los roles solicitados. Si
     * la cuenta ya existía, no modifica su contraseña.
     */
    UserSummary getOrCreateUser(CreateSystemUserRequest request, List<Role> roles);

    /**
     * Crea una cuenta nueva y asigna los roles solicitados. No reutiliza ni adopta
     * una cuenta existente.
     *
     * @throws co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException si el username ya existe
     */
    UserSummary createAccount(CreateSystemUserRequest request, List<Role> roles);

    /**
     * Garantiza la cuenta de un titular cuya identidad ya fue verificada: la crea
     * si no existe, establece la contraseña de esta solicitud aunque la cuenta ya
     * existiera, y garantiza los roles solicitados.
     */
    UserSummary ensureAccount(CreateSystemUserRequest request, List<Role> roles);
}
