package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientSummaryResponse;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientSummaryProjection;
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
import org.springframework.data.domain.Sort;

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
class PatientServiceSearchTest {

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

    private static PatientSummaryProjection row(UUID id, String identification, String first, String last) {
        return new PatientSummaryProjection() {
            public UUID getId() { return id; }
            public String getIdentification() { return identification; }
            public String getFirstName() { return first; }
            public String getLastName() { return last; }
        };
    }

    private Pageable capturedPageOfFindAll() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(patientRepository).findAllSummaries(captor.capture());
        return captor.getValue();
    }

    @Test
    void withoutSearchTermListsEveryPatient() {
        when(patientRepository.findAllSummaries(any())).thenReturn(Page.empty());

        service.search(null, PageRequest.of(2, 10));
        assertThat(capturedPageOfFindAll().getPageNumber()).isEqualTo(2);

        verify(patientRepository, never()).searchSummaries(anyString(), any());
    }

    @Test
    void blankSearchTermIsTreatedAsNoFilter() {
        when(patientRepository.findAllSummaries(any())).thenReturn(Page.empty());

        service.search("   ", PageRequest.of(0, 10));

        verify(patientRepository, never()).searchSummaries(anyString(), any());
    }

    @Test
    void searchTermIsTrimmedCollapsedAndEscapedBeforeQuerying() {
        when(patientRepository.searchSummaries(anyString(), any())).thenReturn(Page.empty());

        service.search("  ana   100%_a\\b ", PageRequest.of(0, 10));

        verify(patientRepository).searchSummaries(org.mockito.ArgumentMatchers.eq("ana 100\\%\\_a\\\\b"), any());
    }

    @Test
    void pageSizeIsCappedAndTheOrderIsNotConfigurable() {
        when(patientRepository.findAllSummaries(any())).thenReturn(Page.empty());

        service.search(null, PageRequest.of(0, 5000, Sort.by("identification").descending()));

        Pageable page = capturedPageOfFindAll();
        assertThat(page.getPageSize()).isEqualTo(50);
        assertThat(page.getSort().isSorted()).isFalse();
    }

    @Test
    void unpagedRequestFallsBackToTheDefaultPage() {
        when(patientRepository.findAllSummaries(any())).thenReturn(Page.empty());

        service.search(null, Pageable.unpaged());

        Pageable page = capturedPageOfFindAll();
        assertThat(page.getPageNumber()).isZero();
        assertThat(page.getPageSize()).isEqualTo(10);
    }

    @Test
    void overlongSearchTermIsRejected() {
        assertThatThrownBy(() -> service.search("a".repeat(101), PageRequest.of(0, 10)))
                .isInstanceOf(InvalidPatientDataException.class);

        verify(patientRepository, never()).searchSummaries(anyString(), any());
        verify(patientRepository, never()).findAllSummaries(any());
    }

    @Test
    void rowsAreMappedToSummaryResponsesKeepingThePagination() {
        UUID id = UUID.randomUUID();
        Pageable request = PageRequest.of(1, 10);
        when(patientRepository.findAllSummaries(any()))
                .thenReturn(new PageImpl<>(List.of(row(id, "1002003004", "Ana", "Ruiz")), request, 11));

        Page<PatientSummaryResponse> result = service.search(null, request);

        assertThat(result.getTotalElements()).isEqualTo(11);
        assertThat(result.getNumber()).isEqualTo(1);
        PatientSummaryResponse only = result.getContent().getFirst();
        assertThat(only.getId()).isEqualTo(id);
        assertThat(only.getIdentification()).isEqualTo("1002003004");
        assertThat(only.getFirstName()).isEqualTo("Ana");
        assertThat(only.getLastName()).isEqualTo("Ruiz");
    }
}
