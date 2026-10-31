package co.edu.unicauca.piedrazul.backend.user.application;

import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.PatientWithoutUserException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServicePatientStatusTest {

    private static final UUID PATIENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private KeycloakUserService keycloakUserService;
    @Mock
    private PersonExternalServiceImp personExternalServiceImp;
    @Mock
    private PatientModuleApi patientModuleApi;

    @InjectMocks
    private UserService service;

    private void givenPatient(UUID userId) {
        when(patientModuleApi.existsById(PATIENT_ID)).thenReturn(true);
        when(personExternalServiceImp.findById(PATIENT_ID)).thenReturn(Optional.of(new PersonSummary(
                PATIENT_ID, userId, IdentificationType.CEDULA, "1002003004",
                "Ana", "Ruiz", "3001234567", "ana@example.com")));
    }

    @Test
    void activatingResolvesTheKeycloakUserFromThePatientIdAndActivatesIt() {
        givenPatient(USER_ID);

        service.activatePatientUser(PATIENT_ID);

        verify(keycloakUserService).activateUser(USER_ID, PATIENT_ID);
    }

    @Test
    void deactivatingResolvesTheKeycloakUserFromThePatientIdAndDeactivatesIt() {
        givenPatient(USER_ID);

        service.deactivatePatientUser(PATIENT_ID);

        verify(keycloakUserService).deactivateUser(USER_ID, PATIENT_ID);
    }

    @Test
    void theKeycloakUserIsTheAccountOfThePersonNotThePatientId() {
        // El id del paciente es el de la persona; el del usuario de Keycloak es otro.
        givenPatient(USER_ID);

        service.deactivatePatientUser(PATIENT_ID);
        service.activatePatientUser(PATIENT_ID);

        verify(keycloakUserService, never()).deactivateUser(eq(PATIENT_ID), any());
        verify(keycloakUserService, never()).activateUser(eq(PATIENT_ID), any());
    }

    @Test
    void aPersonThatIsNotAPatientIsNotFoundEvenIfItHasAnAccount() {
        when(patientModuleApi.existsById(PATIENT_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.activatePatientUser(PATIENT_ID)).isInstanceOf(PersonNotFoundException.class);
        assertThatThrownBy(() -> service.deactivatePatientUser(PATIENT_ID)).isInstanceOf(PersonNotFoundException.class);

        verifyNoInteractions(keycloakUserService, personExternalServiceImp);
    }

    @Test
    void aPatientWhosePersonIsMissingIsNotFound() {
        when(patientModuleApi.existsById(PATIENT_ID)).thenReturn(true);
        when(personExternalServiceImp.findById(PATIENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.activatePatientUser(PATIENT_ID)).isInstanceOf(PersonNotFoundException.class);

        verifyNoInteractions(keycloakUserService);
    }

    @Test
    void aPatientWithoutAccountIsAConflictInBothDirections() {
        givenPatient(null);

        assertThatThrownBy(() -> service.activatePatientUser(PATIENT_ID)).isInstanceOf(PatientWithoutUserException.class);
        assertThatThrownBy(() -> service.deactivatePatientUser(PATIENT_ID)).isInstanceOf(PatientWithoutUserException.class);

        verifyNoInteractions(keycloakUserService);
    }

    @Test
    void aNullIdIsRejected() {
        assertThatThrownBy(() -> service.activatePatientUser(null)).isInstanceOf(InvalidUserDataException.class);
        assertThatThrownBy(() -> service.deactivatePatientUser(null)).isInstanceOf(InvalidUserDataException.class);

        verifyNoInteractions(keycloakUserService, personExternalServiceImp, patientModuleApi);
    }
}
