package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.UserConsultPort;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class UserConsultPortImpl implements UserConsultPort {
    private final UserModuleApi userModuleApi;

    public UserConsultPortImpl(UserModuleApi userModuleApi) {
        this.userModuleApi = userModuleApi;
    }

    @Override
    public List<String> getUserRoles(UUID userId) {
        return userModuleApi.getUserRoles(userId);
    }
}
