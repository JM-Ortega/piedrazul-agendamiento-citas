package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientAccountSummary;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientAccountSummaryProjection;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.VerificationModuleApi;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientServiceSearchPatientsWithAccountTest {

    @Mock
    private PatientRepository patientRepository;
    @Mock
    private PersonExternalService personExternalService;
    @Mock
    private UserModuleApi userModuleApi;
    @Mock
    private UserAccountProvisioningApi userAccountProvisioningApi;
    @Mock
    private VerificationModuleApi verificationModuleApi;
    @Mock
    private PatientLinkFinalizer patientLinkFinalizer;

    @InjectMocks
    private PatientService service;

    private static PatientAccountSummaryProjection row(UUID id, UUID userId, String identification, String first, String last) {
        return new PatientAccountSummaryProjection() {
            public UUID getId() { return id; }
            public String getIdentification() { return identification; }
            public String getFirstName() { return first; }
            public String getLastName() { return last; }
            public UUID getUserId() { return userId; }
        };
    }

    private Pageable capturedPageOfFindAccounted() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(patientRepository).findAccountedSummaries(captor.capture());
        return captor.getValue();
    }

    @Test
    void withoutSearchTermListsEveryAccountedPatient() {
        when(patientRepository.findAccountedSummaries(any())).thenReturn(Page.empty());

        service.searchPatientsWithAccount(null, PageRequest.of(1, 10));

        assertThat(capturedPageOfFindAccounted().getPageNumber()).isEqualTo(1);
        verify(patientRepository, never()).searchAccountedSummaries(anyString(), any());
    }

    @Test
    void blankSearchTermIsTreatedAsNoFilter() {
        when(patientRepository.findAccountedSummaries(any())).thenReturn(Page.empty());

        service.searchPatientsWithAccount("   ", PageRequest.of(0, 10));

        verify(patientRepository, never()).searchAccountedSummaries(anyString(), any());
    }

    @Test
    void searchTermIsTrimmedCollapsedAndEscapedBeforeQuerying() {
        when(patientRepository.searchAccountedSummaries(anyString(), any())).thenReturn(Page.empty());

        service.searchPatientsWithAccount("  ana   100%_a\\b ", PageRequest.of(0, 10));

        verify(patientRepository).searchAccountedSummaries(
                org.mockito.ArgumentMatchers.eq("ana 100\\%\\_a\\\\b"), any());
    }

    @Test
    void pageSizeIsCappedAndTheOrderIsNotConfigurable() {
        when(patientRepository.findAccountedSummaries(any())).thenReturn(Page.empty());

        service.searchPatientsWithAccount(null, PageRequest.of(0, 5000,
                org.springframework.data.domain.Sort.by("identification").descending()));

        Pageable page = capturedPageOfFindAccounted();
        assertThat(page.getPageSize()).isEqualTo(50);
        assertThat(page.getSort().isSorted()).isFalse();
    }

    @Test
    void overlongSearchTermIsRejected() {
        assertThatThrownBy(() -> service.searchPatientsWithAccount("a".repeat(101), PageRequest.of(0, 10)))
                .isInstanceOf(InvalidPatientDataException.class);

        verify(patientRepository, never()).searchAccountedSummaries(anyString(), any());
        verify(patientRepository, never()).findAccountedSummaries(any());
    }

    @Test
    void rowsAreMappedToPatientAccountSummariesKeepingThePagination() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable request = PageRequest.of(1, 10);
        when(patientRepository.findAccountedSummaries(any()))
                .thenReturn(new PageImpl<>(List.of(row(personId, userId, "1002003004", "Ana", "Ruiz")), request, 11));

        Page<PatientAccountSummary> result = service.searchPatientsWithAccount(null, request);

        assertThat(result.getTotalElements()).isEqualTo(11);
        assertThat(result.getNumber()).isEqualTo(1);
        PatientAccountSummary only = result.getContent().getFirst();
        assertThat(only.personId()).isEqualTo(personId);
        assertThat(only.userId()).isEqualTo(userId);
        assertThat(only.identification()).isEqualTo("1002003004");
        assertThat(only.firstName()).isEqualTo("Ana");
        assertThat(only.lastName()).isEqualTo("Ruiz");
    }

    @Test
    void patientsWithoutAnAccountAreNeverIncluded() {
        // La responsabilidad de excluirlos es de la consulta (probado en PatientAccountedSearchIT);
        // aquí solo se comprueba que el servicio delega en la consulta "accounted" y no en la general.
        when(patientRepository.findAccountedSummaries(any())).thenReturn(Page.empty());

        service.searchPatientsWithAccount(null, PageRequest.of(0, 10));

        verify(patientRepository, never()).findAllSummaries(any());
    }
}
