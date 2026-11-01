package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;

public interface UserProvisioningApi {
    void createUser(CreateSystemUserPayload payload);
}