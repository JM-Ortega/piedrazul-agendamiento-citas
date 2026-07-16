package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PersonConsultPort;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;

import java.util.UUID;

public class PersonConsultPortImpl implements PersonConsultPort {
    private final PersonExternalService personExternalService;

    public PersonConsultPortImpl(PersonExternalService personExternalService) {
        this.personExternalService = personExternalService;
    }

    @Override
    public String getPersonName(UUID idDoctor) {
        return personExternalService.getPersonName(idDoctor);
    }
}
