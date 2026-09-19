package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.UpdatePatientCommand;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.UserAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientUpdateServiceTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final LocalDate ADULT_BIRTH = LocalDate.of(1990, 5, 10);
    private static final String DOCUMENT = "1002003004";

    @Mock
    private PatientRepository patientRepository;
    @Mock
    private PersonExternalService personExternalService;
    @Mock
    private UserModuleApi userModuleApi;
    @Mock
    private PatientUpdateFinalizer finalizer;
    @Mock
    private SecurityContextExtractor securityContextExtractor;

    @InjectMocks
    private PatientUpdateService service;

    @BeforeEach
    void setUp() {
        lenient().when(securityContextExtractor.currentActorId()).thenReturn("actor-1");
        lenient().when(securityContextExtractor.currentActorRoles()).thenReturn("[DOCTOR]");
    }

    private PersonSummary person(UUID userId, IdentificationType type, String document) {
        return new PersonSummary(ID, userId, type, document, "Ana", "Ruiz", "3001234567", "ana@example.com");
    }

    private void givenStored(PersonSummary person, LocalDate birthDate, String guardianPhone) {
        when(personExternalService.findById(ID)).thenReturn(Optional.of(person));
        when(patientRepository.findById(ID))
                .thenReturn(Optional.of(new Patient(ID, Sex.FEMENINO, birthDate, guardianPhone)));
    }

    private void givenStoredAdult(UUID userId) {
        givenStored(person(userId, IdentificationType.CEDULA, DOCUMENT), ADULT_BIRTH, null);
    }

    private UpdatePatientCommand command(
            IdentificationType type, String document, String phone, LocalDate birthDate, String guardianPhone) {
        return new UpdatePatientCommand(
                type, document, "Ana", "Ruiz", phone, "ana@example.com",
                PatientSex.FEMENINO, birthDate, guardianPhone);
    }

    private UpdatePatientCommand sameAsStored() {
        return command(IdentificationType.CEDULA, DOCUMENT, "3001234567", ADULT_BIRTH, null);
    }

    private void givenFinalizerReturns() {
        when(finalizer.apply(eq(ID), any(), any(), any(), any(), any()))
                .thenReturn(new PatientData(ID, USER_ID, IdentificationType.CEDULA, DOCUMENT, "Ana", "Ruiz",
                        "3119998877", "ana@example.com", PatientSex.FEMENINO, ADULT_BIRTH, null));
    }

    // ---- sin cambios --------------------------------------------------------

    @Test
    void nothingChangedReturnsCurrentDataWithoutAnySideEffect() {
        givenStoredAdult(USER_ID);

        PatientData result = service.updatePatient(ID, sameAsStored());

        assertThat(result.personId()).isEqualTo(ID);
        assertThat(result.identification()).isEqualTo(DOCUMENT);
        verifyNoInteractions(userModuleApi, finalizer);
        verify(personExternalService, never()).requireIdentificationAvailableFor(any(), any());
    }

    // ---- sincronización con la cuenta ---------------------------------------

    @Test
    void documentChangeWithAccountChecksAvailabilityThenSyncsAccountThenWrites() {
        givenStoredAdult(USER_ID);
        givenFinalizerReturns();
        UpdatePatientCommand next = command(IdentificationType.CEDULA, "9998887776", "3001234567", ADULT_BIRTH, null);

        service.updatePatient(ID, next);

        InOrder order = inOrder(personExternalService, userModuleApi, finalizer);
        order.verify(personExternalService).requireIdentificationAvailableFor(ID, "9998887776");
        order.verify(userModuleApi).updateUserIdentity(USER_ID, "9998887776", "Ana", "Ruiz", "ana@example.com");
        order.verify(finalizer).apply(eq(ID), eq(next), any(), eq("actor-1"), eq("[DOCTOR]"), any());
    }

    @Test
    void phoneOnlyChangeNeverTouchesTheAccountBecauseItDoesNotStoreThePhone() {
        givenStoredAdult(USER_ID);
        givenFinalizerReturns();

        service.updatePatient(ID, command(IdentificationType.CEDULA, DOCUMENT, "3119998877", ADULT_BIRTH, null));

        verifyNoInteractions(userModuleApi);
        verify(personExternalService, never()).requireIdentificationAvailableFor(any(), any());
        verify(finalizer).apply(eq(ID), any(), any(), any(), any(), any());
    }

    @Test
    void personWithoutAccountNeverTouchesTheIdentityProvider() {
        givenStoredAdult(null);
        givenFinalizerReturns();

        service.updatePatient(ID, command(IdentificationType.CEDULA, "9998887776", "3001234567", ADULT_BIRTH, null));

        verifyNoInteractions(userModuleApi);
        verify(finalizer).apply(eq(ID), any(), any(), any(), any(), any());
    }

    @Test
    void documentTypeChangeAloneDoesNotTouchTheAccount() {
        givenStoredAdult(USER_ID);
        givenFinalizerReturns();

        service.updatePatient(ID, command(
                IdentificationType.CEDULA_EXTRANJERIA, DOCUMENT, "3001234567", ADULT_BIRTH, null));

        verifyNoInteractions(userModuleApi);
        verify(finalizer).apply(eq(ID), any(), any(), any(), any(), any());
    }

    // ---- fallos: nada a medias ----------------------------------------------

    @Test
    void databaseFailureRestoresTheAccountWithThePreviousData() {
        givenStoredAdult(USER_ID);
        when(finalizer.apply(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.updatePatient(
                ID, command(IdentificationType.CEDULA, "9998887776", "3001234567", ADULT_BIRTH, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        InOrder order = inOrder(userModuleApi);
        order.verify(userModuleApi).updateUserIdentity(USER_ID, "9998887776", "Ana", "Ruiz", "ana@example.com");
        order.verify(userModuleApi).updateUserIdentity(USER_ID, DOCUMENT, "Ana", "Ruiz", "ana@example.com");
    }

    @Test
    void failedRestoreDoesNotHideTheOriginalError() {
        givenStoredAdult(USER_ID);
        when(finalizer.apply(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        org.mockito.Mockito.doNothing()
                .doThrow(new RuntimeException("keycloak caído"))
                .when(userModuleApi).updateUserIdentity(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.updatePatient(
                ID, command(IdentificationType.CEDULA, "9998887776", "3001234567", ADULT_BIRTH, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    void databaseFailureWithoutAccountSyncDoesNotTouchTheIdentityProvider() {
        givenStoredAdult(USER_ID);
        when(finalizer.apply(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.updatePatient(
                ID, command(IdentificationType.CEDULA, DOCUMENT, "3119998877", ADULT_BIRTH, null)))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(userModuleApi);
    }

    @Test
    void identityProviderFailureNeverWritesTheDatabaseNorTriesToRestore() {
        givenStoredAdult(USER_ID);
        org.mockito.Mockito.doThrow(new UserAlreadyExistsException("correo repetido"))
                .when(userModuleApi).updateUserIdentity(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.updatePatient(
                ID, command(IdentificationType.CEDULA, "9998887776", "3001234567", ADULT_BIRTH, null)))
                .isInstanceOf(UserAlreadyExistsException.class);

        verify(userModuleApi, times(1)).updateUserIdentity(any(), any(), any(), any(), any());
        verifyNoInteractions(finalizer);
    }

    @Test
    void documentAlreadyUsedByAnotherPersonFailsBeforeAnyExternalEffect() {
        givenStoredAdult(USER_ID);
        org.mockito.Mockito.doThrow(new PersonAlreadyExistsException("ya existe"))
                .when(personExternalService).requireIdentificationAvailableFor(ID, "9998887776");

        assertThatThrownBy(() -> service.updatePatient(
                ID, command(IdentificationType.CEDULA, "9998887776", "3001234567", ADULT_BIRTH, null)))
                .isInstanceOf(PersonAlreadyExistsException.class);

        verifyNoInteractions(userModuleApi, finalizer);
    }

    // ---- validación del estado final ----------------------------------------

    @Test
    void adultCannotEndUpWithTarjetaDeIdentidadAndNothingExternalHappens() {
        givenStoredAdult(USER_ID);

        assertThatThrownBy(() -> service.updatePatient(
                ID, command(IdentificationType.TARJETA_IDENTIDAD, DOCUMENT, "3001234567", ADULT_BIRTH, null)))
                .isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("El tipo de documento no corresponde a una persona mayor de edad");

        verifyNoInteractions(userModuleApi, finalizer);
    }

    @Test
    void minorRequiresGuardianPhone() {
        givenStoredAdult(USER_ID);
        LocalDate minorBirth = LocalDate.now().minusYears(10);

        assertThatThrownBy(() -> service.updatePatient(
                ID, command(IdentificationType.TARJETA_IDENTIDAD, "1234567890", "3001234567", minorBirth, null)))
                .isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("El teléfono de familiar es obligatorio para menores de edad");

        verifyNoInteractions(userModuleApi, finalizer);
    }

    @Test
    void minorCannotHaveCedula() {
        givenStoredAdult(USER_ID);
        LocalDate minorBirth = LocalDate.now().minusYears(10);

        assertThatThrownBy(() -> service.updatePatient(
                ID, command(IdentificationType.CEDULA, DOCUMENT, "3001234567", minorBirth, "3007654321")))
                .isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("Un menor de edad no puede tener cédula");
    }

    @Test
    void patientWhoTurnedAdultWithTarjetaMustFixTheDocumentInTheSameEdit() {
        // Registrado a los 17 con TI, hoy adulto: no se puede editar solo el teléfono.
        givenStored(person(USER_ID, IdentificationType.TARJETA_IDENTIDAD, "1234567890"),
                LocalDate.now().minusYears(19), "3007654321");

        assertThatThrownBy(() -> service.updatePatient(ID, command(
                IdentificationType.TARJETA_IDENTIDAD, "1234567890", "3119998877",
                LocalDate.now().minusYears(19), "3007654321")))
                .isInstanceOf(InvalidPatientDataException.class);

        verifyNoInteractions(userModuleApi, finalizer);
    }

    @Test
    void patientWhoTurnedAdultCanBeFixedByChangingTypeAndNumberTogether() {
        givenStored(person(USER_ID, IdentificationType.TARJETA_IDENTIDAD, "1234567890"),
                LocalDate.now().minusYears(19), "3007654321");
        givenFinalizerReturns();

        service.updatePatient(ID, command(
                IdentificationType.CEDULA, "1002003004", "3001234567",
                LocalDate.now().minusYears(19), "3007654321"));

        verify(userModuleApi).updateUserIdentity(USER_ID, "1002003004", "Ana", "Ruiz", "ana@example.com");
        verify(finalizer).apply(eq(ID), any(), any(), any(), any(), any());
    }

    @Test
    void requiredDataIsValidated() {
        givenStoredAdult(USER_ID);

        assertThatThrownBy(() -> service.updatePatient(ID, new UpdatePatientCommand(
                IdentificationType.CEDULA, DOCUMENT, " ", "Ruiz", "3001234567", null,
                PatientSex.FEMENINO, ADULT_BIRTH, null)))
                .isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("El nombre es obligatorio");

        assertThatThrownBy(() -> service.updatePatient(ID, new UpdatePatientCommand(
                IdentificationType.CEDULA, DOCUMENT, "Ana", "Ruiz", "3001234567", null,
                null, ADULT_BIRTH, null)))
                .isInstanceOf(InvalidPatientDataException.class)
                .hasMessage("El sexo del paciente es obligatorio");

        verifyNoInteractions(userModuleApi, finalizer);
    }

    // ---- existencia ---------------------------------------------------------

    @Test
    void unknownPersonIsNotFound() {
        when(personExternalService.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePatient(ID, sameAsStored()))
                .isInstanceOf(PatientNotFoundException.class);

        verifyNoInteractions(userModuleApi, finalizer);
    }

    @Test
    void personThatIsNotAPatientIsNotFound() {
        when(personExternalService.findById(ID))
                .thenReturn(Optional.of(person(USER_ID, IdentificationType.CEDULA, DOCUMENT)));
        when(patientRepository.findById(ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePatient(ID, sameAsStored()))
                .isInstanceOf(PatientNotFoundException.class);

        verifyNoInteractions(userModuleApi, finalizer);
    }

    // ---- edición del propio paciente ----------------------------------------

    @Test
    void ownEditKeepsTheCurrentDocumentEvenIfTheCommandCarriesAnother() {
        when(personExternalService.findByUserId(USER_ID))
                .thenReturn(Optional.of(person(USER_ID, IdentificationType.CEDULA, DOCUMENT)));
        givenStoredAdult(USER_ID);
        givenFinalizerReturns();
        UpdatePatientCommand tampered =
                command(IdentificationType.CEDULA_EXTRANJERIA, "5555555555", "3119998877", ADULT_BIRTH, null);

        service.updateOwnPatient(USER_ID, tampered);

        ArgumentCaptor<UpdatePatientCommand> applied = ArgumentCaptor.forClass(UpdatePatientCommand.class);
        verify(finalizer).apply(eq(ID), applied.capture(), any(), any(), any(), any());
        assertThat(applied.getValue().identificationType()).isEqualTo(IdentificationType.CEDULA);
        assertThat(applied.getValue().identification()).isEqualTo(DOCUMENT);
        assertThat(applied.getValue().phone()).isEqualTo("3119998877");
        verify(personExternalService, never()).requireIdentificationAvailableFor(any(), any());
        verifyNoInteractions(userModuleApi);
    }

    @Test
    void ownEditWithoutDocumentInTheCommandStillWorks() {
        when(personExternalService.findByUserId(USER_ID))
                .thenReturn(Optional.of(person(USER_ID, IdentificationType.CEDULA, DOCUMENT)));
        givenStoredAdult(USER_ID);
        givenFinalizerReturns();
        UpdatePatientCommand fromOwnRequest = new UpdatePatientCommand(
                null, null, "Anabel", "Ruiz", "3001234567", "ana@example.com",
                PatientSex.FEMENINO, ADULT_BIRTH, null);

        service.updateOwnPatient(USER_ID, fromOwnRequest);

        // El cambio de nombre sí llega a la cuenta, conservando el usuario actual.
        verify(userModuleApi).updateUserIdentity(USER_ID, DOCUMENT, "Anabel", "Ruiz", "ana@example.com");
    }

    @Test
    void ownEditOfAnAccountWithoutPersonIsNotFound() {
        when(personExternalService.findByUserId(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateOwnPatient(USER_ID, sameAsStored()))
                .isInstanceOf(PatientNotFoundException.class);

        verifyNoInteractions(finalizer, userModuleApi);
    }
}
