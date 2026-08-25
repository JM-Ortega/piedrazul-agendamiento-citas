package co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.proyections.PersonNameProjection;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.proyections.UserPersonProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonRepository extends JpaRepository<Person, UUID> {

    Optional<Person> findByIdentification(String identification);

    // Devuelve las 5 primeras coincidencias del prefijo
    List<Person> findTop5ByIdentificationStartingWith(String identificationPrefix);

    Optional<Person> findByUserId(UUID userId);

    boolean existsByIdentification(String identification);

    boolean existsByUserId(UUID userId);

    @Query("""
    SELECT p.userId
    FROM Person p
    WHERE p.id = :id
    """)
    UUID findUserIdById(@Param("id") UUID id);

    @Query("""
    SELECT p.id
    FROM Person p
    WHERE p.userId = :userId
    """)
    UUID getPersonIdByUserId(@Param("userId") UUID userId);

    @Query("""
        SELECT
            p.id as id,
            CONCAT(p.firstName, ' ', p.lastName) as fullName
        FROM Person p
        WHERE p.id IN :ids
    """)
    List<PersonNameProjection> findNamesByIds(List<UUID> ids);

    @Query("""
        SELECT new co.edu.unicauca.piedrazul.backend.user.infrastructure.proyections.UserPersonProjection(
            p.userId,
            p.id
        )
        FROM Person p
        WHERE p.userId IN :userIds
    """)
    List<UserPersonProjection> findPersonIdsByUserIds(
            @Param("userIds") Collection<UUID> userIds);

    @Query(
            value = """
        SELECT p.*
        FROM piedrazul.person p
        WHERE p.id IN (:ids)
          AND (
                extensions.immutable_unaccent(lower(p.first_name || ' ' || p.last_name))
                    LIKE extensions.immutable_unaccent(lower('%' || :term || '%')) ESCAPE '\\'
                OR p.identification LIKE ('%' || :term || '%') ESCAPE '\\'
              )
        ORDER BY extensions.immutable_unaccent(lower(p.first_name || ' ' || p.last_name)), p.id
        """,
            countQuery = """
        SELECT COUNT(*)
        FROM piedrazul.person p
        WHERE p.id IN (:ids)
          AND (
                extensions.immutable_unaccent(lower(p.first_name || ' ' || p.last_name))
                    LIKE extensions.immutable_unaccent(lower('%' || :term || '%')) ESCAPE '\\'
                OR p.identification LIKE ('%' || :term || '%') ESCAPE '\\'
              )
        """,
            nativeQuery = true)
    Page<Person> findByIdInAndFullNameOrIdentificationContaining(
            @Param("ids") Collection<UUID> ids,
            @Param("term") String term,
            Pageable pageable);

}
