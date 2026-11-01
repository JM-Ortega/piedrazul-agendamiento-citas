package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonNotFoundException;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.PersonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonExternalServiceImpUpdateTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private PersonRepository personRepository;

    @Mock
    private KeycloakUserService keycloakUserService;

    @InjectMocks
    private PersonExternalServiceImp service;

    private Person stored() {
        return new Person(USER_ID, IdentificationType.TARJETA_IDENTIDAD, "1234567890",
                "Ana", "Ruiz", "3001234567", "ana@example.com");
    }

    private PersonSummary update() {
        return service.updatePerson(ID, IdentificationType.CEDULA, "1002003004",
                "Anabel", "Ruiz Peña", "3119998877", "nuevo@example.com");
    }

    @Test
    void updatePersonReplacesTheMasterDataAndKeepsTheAccountLink() {
        Person person = stored();
        when(personRepository.findById(ID)).thenReturn(Optional.of(person));
        when(personRepository.existsByIdentificationAndIdNot("1002003004", ID)).thenReturn(false);
        when(personRepository.saveAndFlush(person)).thenReturn(person);

        PersonSummary result = update();

        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.identificationType()).isEqualTo(IdentificationType.CEDULA);
        assertThat(result.identification()).isEqualTo("1002003004");
        assertThat(result.firstName()).isEqualTo("Anabel");
        assertThat(result.lastName()).isEqualTo("Ruiz Peña");
        assertThat(result.phone()).isEqualTo("3119998877");
        assertThat(result.email()).isEqualTo("nuevo@example.com");
    }

    @Test
    void updatePersonFailsWhenThePersonDoesNotExist() {
        when(personRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(this::update).isInstanceOf(PersonNotFoundException.class);

        verify(personRepository, never()).saveAndFlush(any());
    }

    @Test
    void updatePersonRejectsADocumentUsedByAnotherPerson() {
        when(personRepository.findById(ID)).thenReturn(Optional.of(stored()));
        when(personRepository.existsByIdentificationAndIdNot("1002003004", ID)).thenReturn(true);

        assertThatThrownBy(this::update).isInstanceOf(PersonAlreadyExistsException.class);

        verify(personRepository, never()).saveAndFlush(any());
    }

    @Test
    void updatePersonTranslatesAConcurrentUniquenessViolation() {
        Person person = stored();
        when(personRepository.findById(ID)).thenReturn(Optional.of(person));
        when(personRepository.existsByIdentificationAndIdNot("1002003004", ID)).thenReturn(false);
        when(personRepository.saveAndFlush(person)).thenThrow(new DataIntegrityViolationException("uq_person_identification"));

        assertThatThrownBy(this::update).isInstanceOf(PersonAlreadyExistsException.class);
    }

    @Test
    void updatePersonValidatesRequiredDataBeforeTouchingTheRepository() {
        assertThatThrownBy(() -> service.updatePerson(
                ID, IdentificationType.CEDULA, "1002003004", " ", "Ruiz", "3119998877", null))
                .isInstanceOf(InvalidUserDataException.class)
                .hasMessage("El nombre es requerido");

        assertThatThrownBy(() -> service.updatePerson(
                null, IdentificationType.CEDULA, "1002003004", "Ana", "Ruiz", "3119998877", null))
                .isInstanceOf(InvalidUserDataException.class);

        verifyNoInteractions(personRepository);
    }

    @Test
    void requireIdentificationAvailableForIgnoresThePersonItself() {
        when(personRepository.existsByIdentificationAndIdNot("1002003004", ID)).thenReturn(false);

        assertThatCode(() -> service.requireIdentificationAvailableFor(ID, "1002003004"))
                .doesNotThrowAnyException();
    }

    @Test
    void requireIdentificationAvailableForRejectsAnotherPersonsDocument() {
        when(personRepository.existsByIdentificationAndIdNot("1002003004", ID)).thenReturn(true);

        assertThatThrownBy(() -> service.requireIdentificationAvailableFor(ID, "1002003004"))
                .isInstanceOf(PersonAlreadyExistsException.class);
    }

    @Test
    void requireIdentificationAvailableForValidatesItsArguments() {
        assertThatThrownBy(() -> service.requireIdentificationAvailableFor(null, "1002003004"))
                .isInstanceOf(InvalidUserDataException.class);
        assertThatThrownBy(() -> service.requireIdentificationAvailableFor(ID, "  "))
                .isInstanceOf(InvalidUserDataException.class);

        verifyNoInteractions(personRepository);
    }
}
