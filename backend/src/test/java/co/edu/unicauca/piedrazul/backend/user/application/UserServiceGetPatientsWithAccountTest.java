package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientAccountSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.output.SystemPatientResponse;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceGetPatientsWithAccountTest {

    @Mock
    private KeycloakUserService keycloakUserService;
    @Mock
    private DoctorExternalService doctorExternalService;
    @Mock
    private PersonExternalServiceImp personExternalServiceImp;
    @Mock
    private PatientModuleApi patientModuleApi;

    @InjectMocks
    private UserService service;

    @Test
    void marksAccountEnabledWhenKeycloakReportsItEnabled() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable request = PageRequest.of(0, 10);
        PatientAccountSummary summary = new PatientAccountSummary(personId, userId, "1002003004", "Ana", "Ruiz");
        when(patientModuleApi.searchPatientsWithAccount("ana", request))
                .thenReturn(new PageImpl<>(List.of(summary), request, 1));
        when(keycloakUserService.getAccountsEnabledStatus(Set.of(userId))).thenReturn(Map.of(userId, true));

        Page<SystemPatientResponse> result = service.getPatientsWithAccount("ana", request);

        SystemPatientResponse only = result.getContent().getFirst();
        assertThat(only.id()).isEqualTo(personId);
        assertThat(only.firstName()).isEqualTo("Ana");
        assertThat(only.lastName()).isEqualTo("Ruiz");
        assertThat(only.documentId()).isEqualTo("1002003004");
        assertThat(only.accountEnabled()).isTrue();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void marksAccountDisabledWhenKeycloakReportsItDisabled() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable request = PageRequest.of(0, 10);
        when(patientModuleApi.searchPatientsWithAccount(any(), any()))
                .thenReturn(new PageImpl<>(List.of(new PatientAccountSummary(personId, userId, "1002003004", "Ana", "Ruiz"))));
        when(keycloakUserService.getAccountsEnabledStatus(any())).thenReturn(Map.of(userId, false));

        Page<SystemPatientResponse> result = service.getPatientsWithAccount(null, request);

        assertThat(result.getContent().getFirst().accountEnabled()).isFalse();
    }

    @Test
    void aMissingKeycloakAnswerForAUserIsTreatedAsDisabledRatherThanFailing() {
        UUID personId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Pageable request = PageRequest.of(0, 10);
        when(patientModuleApi.searchPatientsWithAccount(any(), any()))
                .thenReturn(new PageImpl<>(List.of(new PatientAccountSummary(personId, userId, "1002003004", "Ana", "Ruiz"))));
        // El mapa no trae ese userId (por ejemplo, la cuenta ya no existe en Keycloak).
        when(keycloakUserService.getAccountsEnabledStatus(any())).thenReturn(Map.of());

        Page<SystemPatientResponse> result = service.getPatientsWithAccount(null, request);

        assertThat(result.getContent().getFirst().accountEnabled()).isFalse();
    }

    @Test
    void onlyAsksKeycloakForTheAccountsOfTheCurrentPage() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        Pageable request = PageRequest.of(0, 10);
        when(patientModuleApi.searchPatientsWithAccount(any(), any())).thenReturn(new PageImpl<>(List.of(
                new PatientAccountSummary(UUID.randomUUID(), userId1, "1", "Ana", "Ruiz"),
                new PatientAccountSummary(UUID.randomUUID(), userId2, "2", "Beto", "Gómez")
        )));
        when(keycloakUserService.getAccountsEnabledStatus(any())).thenReturn(Map.of(userId1, true, userId2, false));

        service.getPatientsWithAccount(null, request);

        ArgumentCaptor<Set<UUID>> captor = ArgumentCaptor.forClass(Set.class);
        verify(keycloakUserService).getAccountsEnabledStatus(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(userId1, userId2);
    }

    @Test
    void withoutResultsNothingIsAskedOfKeycloak() {
        Pageable request = PageRequest.of(0, 10);
        when(patientModuleApi.searchPatientsWithAccount(any(), any())).thenReturn(Page.empty(request));
        when(keycloakUserService.getAccountsEnabledStatus(eq(Set.of()))).thenReturn(Map.of());

        Page<SystemPatientResponse> result = service.getPatientsWithAccount(null, request);

        assertThat(result.getContent()).isEmpty();
    }

    @Test
    void delegatesTheSearchTermAndPageableAsIsToThePatientModule() {
        Pageable request = PageRequest.of(2, 15);
        when(patientModuleApi.searchPatientsWithAccount("beto", request)).thenReturn(Page.empty(request));
        when(keycloakUserService.getAccountsEnabledStatus(eq(Set.of()))).thenReturn(Map.of());

        service.getPatientsWithAccount("beto", request);

        verify(patientModuleApi).searchPatientsWithAccount("beto", request);
    }
}
