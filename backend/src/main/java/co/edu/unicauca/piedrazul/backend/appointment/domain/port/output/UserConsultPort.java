package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import java.util.List;
import java.util.UUID;

public interface UserConsultPort {
    List<String> getUserRoles (UUID userId);


}
