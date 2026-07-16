package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyLinkedUserException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonNotFoundException;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.mappers.PersonApiMapper;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.PersonRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PersonExternalServiceImp implements PersonExternalService {

    private final PersonRepository personRepository;
    private final KeycloakUserService keycloakUserService;

    public PersonExternalServiceImp(PersonRepository personRepository, KeycloakUserService keycloakUserService) {
        this.personRepository = personRepository;
        this.keycloakUserService = keycloakUserService;
    }

    @Override
    public PersonSummary createPerson(
            IdentificationType identificationType,
            String identification,
            String firstName,
            String lastName,
            String phone,
            String email,
            UUID userId
    ) {
        if (identificationType == null)
            throw new InvalidUserDataException("identificationType is required");

        if (identification == null || identification.isBlank())
            throw new InvalidUserDataException("identification is required");

        if (firstName == null || firstName.isBlank())
            throw new InvalidUserDataException("firstName is required");

        if (lastName == null || lastName.isBlank())
            throw new InvalidUserDataException("lastName is required");

        if (phone == null || phone.isBlank())
            throw new InvalidUserDataException("phone is required");

        if (personRepository.existsByIdentification(identification)) {
            throw new PersonAlreadyExistsException(identification);
        }

        if (userId != null && personRepository.existsByUserId(userId)) {
            throw PersonAlreadyLinkedUserException.forUserId(userId);
        }

        Person person = new Person(
                userId,
                identificationType,
                identification,
                firstName,
                lastName,
                phone,
                email
        );

        return PersonApiMapper.toSummary(personRepository.save(person));
    }

    @Override
    public void linkUserId(UUID personId, UUID userId) {
        if (personId == null)
            throw new InvalidUserDataException("personId is required");

        if (userId == null)
            throw new InvalidUserDataException("userId is required");

        Person person = personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException(personId));

        if (person.getUserId() != null) {
            throw PersonAlreadyLinkedUserException.forPerson(personId);
        }

        if (personRepository.existsByUserId(userId)) {
            throw PersonAlreadyLinkedUserException.forUserId(userId);
        }

        person.setUserId(userId);
        personRepository.save(person);
    }

    @Override
    public Optional<PersonSummary> findById(UUID id) {
        return personRepository.findById(id).map(PersonApiMapper::toSummary);
    }

    @Override
    public Map<UUID, PersonSummary> findByIds(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }

        return personRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Person::getId, PersonApiMapper::toSummary));
    }

    @Override
    public Optional<PersonSummary> findByIdentification(String identification) {
        return personRepository.findByIdentification(identification).map(PersonApiMapper::toSummary);
    }

    @Override
    public List<PersonSummary> findByIdentificationPrefix(String identificationPrefix) {
        return personRepository.findByIdentificationStartingWith(identificationPrefix).stream()
                .map(PersonApiMapper::toSummary)
                .toList();
    }

    @Override
    public Optional<PersonSummary> findByUserId(UUID userId) {
        return personRepository.findByUserId(userId).map(PersonApiMapper::toSummary);
    }

    @Override
    public void deactivateUser (UUID personID){
        keycloakUserService.deactivateUser(personRepository.findUserIdById(personID));
    }

    @Override
    public void activateUser(UUID personID){
        keycloakUserService.activateUser(personRepository.findUserIdById(personID));
    }
}
