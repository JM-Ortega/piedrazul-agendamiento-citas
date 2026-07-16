package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import java.util.UUID;

public interface PersonConsultPort {
    String getPersonName(UUID idDoctor);
}
