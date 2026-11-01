package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonExternalServiceImpTest {

    @Mock
    private co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.PersonRepository personRepository;

    @Mock
    private KeycloakUserService keycloakUserService;

    @InjectMocks
    private PersonExternalServiceImp personExternalServiceImp;

    private static final UUID ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Pageable VALID_PAGEABLE = PageRequest.of(0, 10);

    @Test
    void findByIdsAndNameContainingShouldThrowWhenIdsIsNull() {
        assertThrows(InvalidUserDataException.class,
                () -> personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(null, "ort", VALID_PAGEABLE));

        verifyNoInteractions(personRepository);
    }

    @Test
    void findByIdsAndNameContainingShouldThrowWhenTermIsNull() {
        assertThrows(InvalidUserDataException.class,
                () -> personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(ID_1), null, VALID_PAGEABLE));

        verifyNoInteractions(personRepository);
    }

    @Test
    void findByIdsAndNameContainingShouldThrowWhenTermIsBlank() {
        assertThrows(InvalidUserDataException.class,
                () -> personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(ID_1), "   ", VALID_PAGEABLE));

        verifyNoInteractions(personRepository);
    }

    @Test
    void findByIdsAndNameContainingShouldThrowWhenTermIsOnlyWhitespaceAfterCollapsing() {
        // colapsa a un solo espacio, que sigue estando en blanco tras trim
        assertThrows(InvalidUserDataException.class,
                () -> personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(ID_1), "  \t  \n ", VALID_PAGEABLE));

        verifyNoInteractions(personRepository);
    }

    @Test
    void findByIdsAndNameContainingShouldThrowWhenPageableIsNull() {
        assertThrows(InvalidUserDataException.class,
                () -> personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(ID_1), "ort", null));

        verifyNoInteractions(personRepository);
    }

    @Test
    void findByIdsAndNameContainingShouldThrowWhenPageableIsUnpaged() {
        assertThrows(InvalidUserDataException.class,
                () -> personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(ID_1), "ort", Pageable.unpaged()));

        verifyNoInteractions(personRepository);
    }

    @Test
    void findByIdsAndNameContainingShouldThrowWhenPageableHasCustomSort() {
        Pageable sortedPageable = PageRequest.of(0, 10, Sort.by("firstName"));

        assertThrows(InvalidUserDataException.class,
                () -> personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(ID_1), "ort", sortedPageable));

        verifyNoInteractions(personRepository);
    }

    @Test
    void findByIdsAndNameOrIdentificationContainingShouldReturnEmptyPageWithoutQueryingWhenIdsIsEmpty() {
        Page<PersonSummary> result = personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(), "ort", VALID_PAGEABLE);

        assertTrue(result.isEmpty());
        assertEquals(0, result.getTotalElements());
        verify(personRepository, never()).findByIdInAndFullNameOrIdentificationContaining(any(), any(), any());
    }

    @Test
    void findByIdsAndNameOrIdentificationContainingShouldCollapseRepeatedWhitespaceBeforeQuerying() {
        when(personRepository.findByIdInAndFullNameOrIdentificationContaining(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(ID_1), "juan   ort", VALID_PAGEABLE);

        ArgumentCaptor<String> termCaptor = ArgumentCaptor.forClass(String.class);
        verify(personRepository).findByIdInAndFullNameOrIdentificationContaining(eq(Set.of(ID_1)), termCaptor.capture(), eq(VALID_PAGEABLE));

        assertEquals("juan ort", termCaptor.getValue());
    }

    @Test
    void findByIdsAndNameOrIdentificationContainingShouldEscapeLikeWildcardsAndBackslash() {
        when(personRepository.findByIdInAndFullNameOrIdentificationContaining(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(ID_1), "100%_\\ana", VALID_PAGEABLE);

        ArgumentCaptor<String> termCaptor = ArgumentCaptor.forClass(String.class);
        verify(personRepository).findByIdInAndFullNameOrIdentificationContaining(eq(Set.of(ID_1)), termCaptor.capture(), eq(VALID_PAGEABLE));

        assertEquals("100\\%\\_\\\\ana", termCaptor.getValue());
    }

    @Test
    void findByIdsAndNameOrIdentificationContainingShouldMapRepositoryPageToPersonSummaryPage() {
        Person person = new Person(
                null,
                IdentificationType.CEDULA,
                "1234567890",
                "Juan",
                "Ortega",
                "3000000000",
                null
        );
        person.setId(ID_1);

        when(personRepository.findByIdInAndFullNameOrIdentificationContaining(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(person), VALID_PAGEABLE, 1));

        Page<PersonSummary> result = personExternalServiceImp.findByIdsAndNameOrIdentificationContaining(Set.of(ID_1), "ort", VALID_PAGEABLE);

        assertEquals(1, result.getTotalElements());
        assertEquals(ID_1, result.getContent().get(0).id());
        assertEquals("Juan", result.getContent().get(0).firstName());
        assertEquals("Ortega", result.getContent().get(0).lastName());
    }
}
