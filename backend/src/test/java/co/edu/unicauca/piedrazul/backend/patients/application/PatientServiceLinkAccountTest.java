package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyLinkedUserException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.verification.VerificationModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientServiceLinkAccountTest {

    private static final String DOC = "1061234567";
    private static final String CODE = "123456";
    private static final LocalDate ADULT = LocalDate.now().minusYears(30);

    private final UUID personId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

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
    @Mock
    private VerifiedCode verifiedCode;

    @InjectMocks
    private PatientService service;

    private PersonSummary person(UUID linkedUserId) {
        return new PersonSummary(
                personId, linkedUserId, IdentificationType.CEDULA, DOC,
                "Ana", "Ruiz", "3001234567", "ana@example.com");
    }

    private Patient patient() {
        return new Patient(personId, Sex.FEMENINO, ADULT, null);
    }

    private void givenState(UUID linkedUserId, boolean patientExists, boolean hasPatientRole) {
        when(personExternalService.findByIdentification(DOC)).thenReturn(Optional.of(person(linkedUserId)));
        when(patientRepository.existsById(personId)).thenReturn(patientExists);
        if (linkedUserId != null && patientExists) {
            when(userModuleApi.getUserRoles(linkedUserId))
                    .thenReturn(hasPatientRole ? List.of(Role.PATIENT.name()) : List.of(Role.DOCTOR.name()));
        }
    }

    private void givenVerificationSucceeds() {
        when(verificationModuleApi.verifyCode(DOC, VerificationPurpose.LINK_PATIENT_ACCOUNT, CODE))
                .thenReturn(verifiedCode);
    }

    private void givenReloadSucceeds(UUID linkedUserId) {
        when(personExternalService.findById(personId)).thenReturn(Optional.of(person(linkedUserId)));
        when(patientRepository.findById(personId)).thenReturn(Optional.of(patient()));
    }

    private void confirm(String password, PatientSex sex, LocalDate birthDate, String guardianPhone) {
        service.confirmLinkUserAccount(DOC, CODE, password, sex, birthDate, guardianPhone);
    }

    @Test
    void stateB_shouldClaimOtpBeforeIdentityProviderAndGrantRoleLast() {
        givenState(null, true, false);
        givenVerificationSucceeds();
        when(userAccountProvisioningApi.ensureAccount(any(), anyList()))
                .thenReturn(new UserSummary(userId, DOC, "Ana", "Ruiz", "ana@example.com", List.of()));
        givenReloadSucceeds(userId);

        confirm("Secreta123", null, null, null);

        // El código se reclama en exclusiva antes de escribir credenciales, y el rol
        // se otorga al final: si el vínculo falla, no queda acceso sin persona ligada.
        InOrder order = inOrder(patientLinkFinalizer, userAccountProvisioningApi, userModuleApi);
        order.verify(patientLinkFinalizer).consumeOtp(verifiedCode);
        order.verify(userAccountProvisioningApi).ensureAccount(any(), anyList());
        order.verify(patientLinkFinalizer).linkUserAccount(personId, userId);
        order.verify(userModuleApi).ensurePatientRole(userId);
    }

    @Test
    void stateB_shouldProvisionAccountWithoutAnyRole() {
        givenState(null, true, false);
        givenVerificationSucceeds();
        when(userAccountProvisioningApi.ensureAccount(any(), anyList()))
                .thenReturn(new UserSummary(userId, DOC, "Ana", "Ruiz", "ana@example.com", List.of()));
        givenReloadSucceeds(userId);

        confirm("Secreta123", null, null, null);

        ArgumentCaptor<List<Role>> roles = ArgumentCaptor.forClass(List.class);
        verify(userAccountProvisioningApi).ensureAccount(any(), roles.capture());
        assertTrue(roles.getValue().isEmpty(), "ensureAccount debe recibir la lista de roles vacía");
    }

    @Test
    void stateB_shouldRequirePassword() {
        givenState(null, true, false);

        assertThrows(InvalidPatientDataException.class, () -> confirm(null, null, null, null));

        verify(verificationModuleApi, never()).verifyCode(anyString(), any(), anyString());
        verify(patientLinkFinalizer, never()).consumeOtp(any());
    }

    @Test
    void stateC_shouldRegisterPatientInsideTransactionAndGrantRoleAfterwards() {
        givenState(userId, false, false);
        givenVerificationSucceeds();
        givenReloadSucceeds(userId);

        confirm(null, PatientSex.FEMENINO, ADULT, null);

        // El rol se otorga después de registrar el paciente, para no habilitar
        // acceso sin su registro.
        InOrder order = inOrder(patientLinkFinalizer, userModuleApi);
        order.verify(patientLinkFinalizer)
                .consumeOtpAndRegisterPatient(any(), any(), any(), any(), any());
        order.verify(userModuleApi).ensurePatientRole(userId);

        verify(userAccountProvisioningApi, never()).ensureAccount(any(), anyList());
        verify(patientLinkFinalizer, never()).linkUserAccount(any(), any());
    }

    @Test
    void stateC_shouldNotRequirePassword() {
        givenState(userId, false, false);
        givenVerificationSucceeds();
        givenReloadSucceeds(userId);

        confirm(null, PatientSex.FEMENINO, ADULT, null);

        verify(patientLinkFinalizer)
                .consumeOtpAndRegisterPatient(any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPatientDataForStateC")
    void stateC_shouldRejectInvalidDataBeforeVerifyingTheCode(
            String caseName, PatientSex sex, LocalDate birthDate, String guardianPhone) {

        givenState(userId, false, false);

        assertThrows(InvalidPatientDataException.class,
                () -> confirm(null, sex, birthDate, guardianPhone));

        verify(verificationModuleApi, never()).verifyCode(anyString(), any(), anyString());
        verify(patientLinkFinalizer, never())
                .consumeOtpAndRegisterPatient(any(), any(), any(), any(), any());
        verify(userModuleApi, never()).ensurePatientRole(any());
    }

    static Stream<Arguments> invalidPatientDataForStateC() {
        return Stream.of(
                Arguments.of("sexo ausente", null, ADULT, null),
                Arguments.of("fecha de nacimiento ausente", PatientSex.FEMENINO, null, null),
                Arguments.of("menor sin teléfono de familiar",
                        PatientSex.FEMENINO, LocalDate.now().minusYears(10), null),
                Arguments.of("fecha de nacimiento futura",
                        PatientSex.FEMENINO, LocalDate.now().plusDays(1), null),
                Arguments.of("cédula incompatible con la edad",
                        PatientSex.FEMENINO, LocalDate.now().minusYears(10), "3001234567")
        );
    }

    @Test
    void stateD_shouldOnlyConsumeOtpAndGrantRoleWithoutRecreatingPatient() {
        givenState(userId, true, false);
        givenVerificationSucceeds();
        givenReloadSucceeds(userId);

        confirm(null, null, null, null);

        InOrder order = inOrder(patientLinkFinalizer, userModuleApi);
        order.verify(patientLinkFinalizer).consumeOtp(verifiedCode);
        order.verify(userModuleApi).ensurePatientRole(userId);

        verify(patientLinkFinalizer, never())
                .consumeOtpAndRegisterPatient(any(), any(), any(), any(), any());
        verify(userAccountProvisioningApi, never()).ensureAccount(any(), anyList());
    }

    @Test
    void stateE_shouldRejectOnConfirm() {
        givenState(userId, true, true);

        assertThrows(PatientAlreadyLinkedUserException.class, () -> confirm(null, null, null, null));

        verify(verificationModuleApi, never()).verifyCode(anyString(), any(), anyString());
    }

    @Test
    void stateE_shouldRejectOnRequestCode() {
        givenState(userId, true, true);

        assertThrows(PatientAlreadyLinkedUserException.class,
                () -> service.requestLinkUserAccountCode(DOC));

        verify(verificationModuleApi, never())
                .requestCode(anyString(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void stateF_shouldRejectWithDomainErrorInsteadOfNullPointer() {
        givenState(null, false, false);

        assertThrows(InvalidPatientDataException.class, () -> confirm(null, null, null, null));
    }

    @Test
    void stateA_shouldRejectWhenPersonDoesNotExist() {
        when(personExternalService.findByIdentification(DOC)).thenReturn(Optional.empty());

        assertThrows(PatientNotFoundException.class, () -> service.requestLinkUserAccountCode(DOC));
    }

    @Test
    void requestCode_shouldBeAllowedForStateC() {
        givenState(userId, false, false);

        service.requestLinkUserAccountCode(DOC);

        verify(verificationModuleApi).requestCode(
                DOC, VerificationPurpose.LINK_PATIENT_ACCOUNT, "Ana Ruiz",
                "3001234567", "ana@example.com", personId);
    }

    @Test
    void requestCode_shouldBeAllowedForStateD() {
        givenState(userId, true, false);

        service.requestLinkUserAccountCode(DOC);

        verify(verificationModuleApi).requestCode(
                DOC, VerificationPurpose.LINK_PATIENT_ACCOUNT, "Ana Ruiz",
                "3001234567", "ana@example.com", personId);
    }
}
