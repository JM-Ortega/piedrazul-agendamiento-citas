package co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
