package co.edu.unicauca.piedrazul.backend.user;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * Busca, entre las personas cuyo id esté en {@code ids}, aquellas cuyo
     * nombre completo (nombres y apellidos) contenga el término dado, sin
     * distinguir mayúsculas ni tildes. El resultado se ordena de forma estable
     * y definida por la propia búsqueda; no admite un {@link
     * org.springframework.data.domain.Sort} distinto.
     *
     * @throws InvalidUserDataException si {@code ids} es nulo; si {@code term}
     * es nulo o queda en blanco tras normalizar espacios; si {@code pageable}
     * es nulo, no está paginado, o trae un sort personalizado.
     */
    Page<PersonSummary> findByIdsAndNameContaining(Collection<UUID> ids, String term, Pageable pageable);
}
