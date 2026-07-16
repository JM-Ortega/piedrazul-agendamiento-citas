package co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.proyections.PersonNameProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PersonRepository extends JpaRepository<Person, UUID> {

    Optional<Person> findByIdentification(String identification);

    List<Person> findByIdentificationStartingWith(String identificationPrefix);

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

}
