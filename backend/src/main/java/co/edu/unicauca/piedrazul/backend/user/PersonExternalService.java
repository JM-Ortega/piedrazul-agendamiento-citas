package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;

import java.util.*;

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

    void deletePerson(UUID personId);

    void linkUserId(UUID personId, UUID userId);

    Optional<PersonSummary> findById(UUID id);

    Map<UUID, PersonSummary> findByIds(Set<UUID> ids);

    Optional<PersonSummary> findByIdentification(String identification);

    List<PersonSummary> findByIdentificationPrefix(String identificationPrefix);

    Optional<PersonSummary> findByUserId(UUID userId);

    void deactivateUser (UUID personID);

    void activateUser(UUID personID);

    String getPersonName(UUID personID);

    Map<UUID, String> getPersonNames(List<UUID> personIds);

    public Map<UUID, UUID> findPersonIdsByUserIds(Collection<UUID> userIds);

    UUID findPersonIdByUserId(UUID userId);
}
