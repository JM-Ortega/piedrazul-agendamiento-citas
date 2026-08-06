package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;

import java.util.List;

public interface UserAccountProvisioningApi {
    UserSummary getOrCreateUser(CreateSystemUserRequest request, List<Role> roles);
}