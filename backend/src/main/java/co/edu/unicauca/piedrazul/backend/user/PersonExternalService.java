package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import org.springframework.data.domain.Page;

import java.awt.print.Pageable;
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

    void revokeDoctorRole(UUID personID);

    void ensureDoctorRole(UUID personID);

    String getPersonName(UUID personID);

    Map<UUID, String> getPersonNames(List<UUID> personIds);

    Map<UUID, UUID> findPersonIdsByUserIds(Collection<UUID> userIds);

    UUID findPersonIdByUserId(UUID userId);
}
