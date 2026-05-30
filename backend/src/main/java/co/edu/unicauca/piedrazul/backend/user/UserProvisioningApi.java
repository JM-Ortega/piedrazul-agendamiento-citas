package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserPayload;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;

public interface UserProvisioningApi {
    UserSummary createUser(CreateSystemUserPayload request);
}