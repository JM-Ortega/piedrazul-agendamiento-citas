package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PersonExternalService {

    PersonSummary createPerson(
            IdentificationType identificationType,
            String identification,
            String firstName,
            String lastName,
            String phone,
            String email,
            UUID userId
    );

    void linkUserId(UUID personId, UUID userId);

    Optional<PersonSummary> findById(UUID id);

    Map<UUID, PersonSummary> findByIds(Set<UUID> ids);

    Optional<PersonSummary> findByIdentification(String identification);

    List<PersonSummary> findByIdentificationPrefix(String identificationPrefix);

    Optional<PersonSummary> findByUserId(UUID userId);
}
