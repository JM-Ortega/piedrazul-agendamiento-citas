package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import co.edu.unicauca.piedrazul.backend.user.events.UserCreatedEvent;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyLinkedUserException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonNotFoundException;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.mappers.PersonApiMapper;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.PersonRepository;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.proyections.PersonNameProjection;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.proyections.UserPersonProjection;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class PersonExternalServiceImp implements PersonExternalService {

    private final PersonRepository personRepository;
    private final KeycloakUserService keycloakUserService;

    public PersonExternalServiceImp(PersonRepository personRepository,
                                    KeycloakUserService keycloakUserService) {
        this.personRepository = personRepository;
        this.keycloakUserService = keycloakUserService;
    }

    @Transactional
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
            throw new InvalidUserDataException("El tipo de identificación es requerido");

        if (identification == null || identification.isBlank())
            throw new InvalidUserDataException("La identificación es requerida");

        if (firstName == null || firstName.isBlank())
            throw new InvalidUserDataException("El nombre es requerido");

        if (lastName == null || lastName.isBlank())
            throw new InvalidUserDataException("El apellido es requerido");

        if (phone == null || phone.isBlank())
            throw new InvalidUserDataException("El número de celular es requerido");

        if (personRepository.existsByIdentification(identification)) {
            throw new PersonAlreadyExistsException("Ya existe una persona con identificación '" + identification + "'");
        }

        if (userId != null && personRepository.existsByUserId(userId)) {
            throw new PersonAlreadyLinkedUserException("La cuenta de usuario " + userId + " ya está vinculada a otra persona");
        }

        Person person = personRepository.save(new Person(
                userId,
                identificationType,
                identification,
                firstName,
                lastName,
                phone,
                email)
        );

        return PersonApiMapper.toSummary(person);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireIdentificationAvailable(String identification) {
        if (identification == null || identification.isBlank()) {
            throw new InvalidUserDataException("La identificación es requerida");
        }

        if (personRepository.existsByIdentification(identification)) {
            throw new PersonAlreadyExistsException(
                    "Ya existe una persona con esa identificación");
        }
    }

    @Override
    public void deletePerson(UUID personId) {
        if (personId == null) {
            throw new InvalidUserDataException("El id de la persona es requerido");
        }

        personRepository.deleteById(personId);
    }

    @Override
    public void linkUserId(UUID personId, UUID userId) {
        if (personId == null)
            throw new InvalidUserDataException("El id de la persona es requerido");

        if (userId == null)
            throw new InvalidUserDataException("El id del usuario es requerido");

        Person person = personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException("No se encontró una persona con id: " + personId));

        if (person.getUserId() != null) {
            throw new PersonAlreadyLinkedUserException("La persona con id " + personId + " ya tiene una cuenta de usuario vinculada");
        }

        if (personRepository.existsByUserId(userId)) {
            throw new PersonAlreadyLinkedUserException("La cuenta de usuario " + userId + " ya está vinculada a otra persona");
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
        return personRepository
                .findTop5ByIdentificationStartingWith(identificationPrefix)
                .stream()
                .map(PersonApiMapper::toSummary)
                .toList();
    }

    @Override
    public Optional<PersonSummary> findByUserId(UUID userId) {
        return personRepository.findByUserId(userId).map(PersonApiMapper::toSummary);
    }

    @Override
    public void revokeDoctorRole(UUID personId){
        keycloakUserService.revokeDoctorRole(personRepository.findUserIdById(personId));
    }

    @Override
    public void ensureDoctorRole(UUID personId){
        keycloakUserService.ensureDoctorRole(personRepository.findUserIdById(personId));
    }

    @Override
    public String getPersonName(UUID personId) {
        Person person = personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException("No se encontró una persona con id: " + personId));

        return person.getFirstName() + " " + person.getLastName();
    }

    @Override
    public Map<UUID, String> getPersonNames(List<UUID> ids) {

        return personRepository.findNamesByIds(ids)
                .stream()
                .collect(Collectors.toMap(
                        PersonNameProjection::getId,
                        PersonNameProjection::getFullName
                ));
    }

    @Override
    public Map<UUID, UUID> findPersonIdsByUserIds(Collection<UUID> userIds) {

        return personRepository.findPersonIdsByUserIds(userIds)
                .stream()
                .collect(Collectors.toMap(
                        UserPersonProjection::userId,
                        UserPersonProjection::personId
                ));
    }

    @Override
    public UUID findPersonIdByUserId(UUID userId){
        return personRepository.getPersonIdByUserId(userId);
    }

    @Override
    public Page<PersonSummary> findByIdsAndNameContaining(Collection<UUID> ids, String term, Pageable pageable) {
        if (ids == null) {
            throw new InvalidUserDataException("El id del usaurio es requerido");
        }
        if (term == null || term.isBlank()) {
            throw new InvalidUserDataException("El usuario es requerido");
        }
        if (pageable == null) {
            throw new InvalidUserDataException("La paginación es requerida");
        }
        if (pageable.isUnpaged()) {
            throw new InvalidUserDataException("La paginación es obligatoria");
        }
        if (pageable.getSort().isSorted()) {
            throw new InvalidUserDataException("No es posible aplicar el ordenamiento");
        }

        String normalized = term.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            throw new InvalidUserDataException("El usuario no es valido");
        }

        if (ids.isEmpty()) {
            return Page.empty(pageable);
        }

        String escaped = normalized
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        return personRepository.findByIdInAndFullNameContaining(ids, escaped, pageable)
                .map(PersonApiMapper::toSummary);
    }
}
