package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyLinkedUserException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.VerificationModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {


    @Mock
    private PatientRepository patientRepository;

    @Mock
    private UserModuleApi userModuleApi;

    @Mock
    private VerificationModuleApi verificationModuleApi;

    @InjectMocks
    private PatientService patientService;

    @Test
    void createPatientShouldCreateAndReturnDataWhenValid() {
        Patient patient = buildPatient(null);

        when(patientRepository.existsByDocumentNumber("123")).thenReturn(false);
        when(patientRepository.save(any())).thenReturn(patient);

        PatientData result = patientService.createPatient(
                PatientDocumentType.CEDULA,
                "123",
                "Juan",
                "Perez",
                "300",
                "mail@test.com",
                PatientGender.MASCULINO,
                LocalDate.of(2000, 1, 1),
                "111"
        );

        assertThat(result).isNotNull();
        verify(patientRepository).save(any());
    }

    @Test
    void createPatientShouldThrowWhenAlreadyExists() {
        when(patientRepository.existsByDocumentNumber("123")).thenReturn(true);

        assertThatThrownBy(() -> patientService.createPatient(
                PatientDocumentType.CEDULA,
                "123",
                "Juan",
                "Perez",
                "300",
                "mail@test.com",
                PatientGender.MASCULINO,
                LocalDate.now(),
                "111"
        )).isInstanceOf(PatientAlreadyExistsException.class);

        verify(patientRepository, never()).save(any());
    }

    @Test
    void createPatientWithUserShouldCreateUserAndPatientWhenSystemUserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        Patient patient = buildPatient(userId);

        when(patientRepository.existsByDocumentNumber("123")).thenReturn(false);
        when(userModuleApi.findUserIdByUsername("123"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(userModuleApi.getOrCreatePatientUser("123", "Juan", "Perez", "mail@test.com", "Pass123!"))
                .thenReturn(userId);
        when(patientRepository.save(any())).thenReturn(patient);

        PatientData result = patientService.createPatientWithUser(
                "123",
                "Pass123!",
                PatientDocumentType.CEDULA,
                "123",
                "Juan",
                "Perez",
                "300",
                "mail@test.com",
                PatientGender.MASCULINO,
                LocalDate.now(),
                "111"
        );

        assertThat(result).isNotNull();
        verify(userModuleApi).getOrCreatePatientUser("123", "Juan", "Perez", "mail@test.com", "Pass123!");
        verify(userModuleApi, never()).ensurePatientRole(any());
        verify(patientRepository).save(any());
    }

    @Test
    void createPatientWithUserShouldReuseExistingUserAndEnsurePatientRole() {
        UUID userId = UUID.randomUUID();
        Patient patient = buildPatient(userId);

        when(patientRepository.existsByDocumentNumber("123")).thenReturn(false);
        when(userModuleApi.findUserIdByUsername("123"))
                .thenReturn(Optional.of(userId), Optional.of(userId));
        when(patientRepository.save(any())).thenReturn(patient);

        PatientData result = patientService.createPatientWithUser(
                "123",
                "Pass123!",
                PatientDocumentType.CEDULA,
                "123",
                "Juan",
                "Perez",
                "300",
                "mail@test.com",
                PatientGender.MASCULINO,
                LocalDate.now(),
                "111"
        );

        assertThat(result).isNotNull();
        verify(userModuleApi, never()).getOrCreatePatientUser(any(), any(), any(), any(), any());
        verify(userModuleApi).ensurePatientRole(userId);
        verify(patientRepository).save(any());
    }

    @Test
    void createPatientWithUserShouldThrowWhenUsernameInvalid() {
        assertThatThrownBy(() -> patientService.createPatientWithUser(
                " ",
                "Pass123!",
                PatientDocumentType.CEDULA,
                "123",
                "Juan",
                "Perez",
                "300",
                "mail@test.com",
                PatientGender.MASCULINO,
                LocalDate.now(),
                "111"
        )).isInstanceOf(InvalidPatientDataException.class)
          .hasMessage("Username cannot be blank");

        verifyNoInteractions(userModuleApi);
    }

    @Test
    void createPatientWithUserShouldThrowWhenUsernameDoesNotMatchDocumentNumber() {
        assertThatThrownBy(() -> patientService.createPatientWithUser(
                "juan",
                "Pass123!",
                PatientDocumentType.CEDULA,
                "123",
                "Juan",
                "Perez",
                "300",
                "mail@test.com",
                PatientGender.MASCULINO,
                LocalDate.now(),
                "111"
        )).isInstanceOf(InvalidPatientDataException.class)
          .hasMessage("Username must match document number");

        verifyNoInteractions(userModuleApi);
        verify(patientRepository, never()).save(any());
    }

    @Test
    void requestLinkUserAccountCodeShouldRequestCodeWhenPatientIsValid() {
        Patient patient = buildPatient(null);

        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.of(patient));

        patientService.requestLinkUserAccountCode("123");

        verify(verificationModuleApi).requestCode(
                "123",
                VerificationPurpose.LINK_PATIENT_ACCOUNT,
                "300"
        );
    }

    @Test
    void requestLinkUserAccountCodeShouldThrowWhenPatientNotFound() {
        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.requestLinkUserAccountCode("123"))
                .isInstanceOf(PatientNotFoundException.class);

        verifyNoInteractions(verificationModuleApi);
    }

    @Test
    void requestLinkUserAccountCodeShouldThrowWhenAlreadyLinked() {
        Patient patient = buildPatient(UUID.randomUUID());

        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> patientService.requestLinkUserAccountCode("123"))
                .isInstanceOf(PatientAlreadyLinkedUserException.class);

        verifyNoInteractions(verificationModuleApi);
    }

    @Test
    void confirmLinkUserAccountShouldCreateAndLinkUserWhenSystemUserDoesNotExist() {
        UUID userId = UUID.randomUUID();
        Patient patient = buildPatient(null);

        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.of(patient));
        when(userModuleApi.findUserIdByUsername("123")).thenReturn(Optional.empty());
        when(userModuleApi.getOrCreatePatientUser("123", "Juan", "Perez", "mail@test.com", "Pass123!"))
                .thenReturn(userId);
        when(patientRepository.save(any())).thenReturn(patient);

        PatientData result = patientService.confirmLinkUserAccount(
                "123",
                "999999",
                "Pass123!"
        );

        assertThat(result).isNotNull();
        assertThat(patient.getUserId()).isEqualTo(userId);

        verify(verificationModuleApi).verifyCode(
                "123",
                VerificationPurpose.LINK_PATIENT_ACCOUNT,
                "999999"
        );
        verify(userModuleApi).getOrCreatePatientUser("123", "Juan", "Perez", "mail@test.com", "Pass123!");
        verify(userModuleApi, never()).ensurePatientRole(any());
        verify(patientRepository).save(patient);
    }

    @Test
    void confirmLinkUserAccountShouldReuseExistingUserAndEnsurePatientRole() {
        UUID userId = UUID.randomUUID();
        Patient patient = buildPatient(null);

        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.of(patient));
        when(userModuleApi.findUserIdByUsername("123")).thenReturn(Optional.of(userId));
        when(patientRepository.save(any())).thenReturn(patient);

        PatientData result = patientService.confirmLinkUserAccount(
                "123",
                "999999",
                "ignored-password"
        );

        assertThat(result).isNotNull();
        assertThat(patient.getUserId()).isEqualTo(userId);

        verify(verificationModuleApi).verifyCode(
                "123",
                VerificationPurpose.LINK_PATIENT_ACCOUNT,
                "999999"
        );
        verify(userModuleApi, never()).getOrCreatePatientUser(any(), any(), any(), any(), any());
        verify(userModuleApi).ensurePatientRole(userId);
        verify(patientRepository).save(patient);
    }

    @Test
    void confirmLinkUserAccountShouldThrowWhenPatientNotFound() {
        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientService.confirmLinkUserAccount(
                "123",
                "999999",
                "Pass123!"
        )).isInstanceOf(PatientNotFoundException.class);

        verify(verificationModuleApi, never()).verifyCode(any(), any(), any());
    }

    @Test
    void confirmLinkUserAccountShouldThrowWhenAlreadyLinked() {
        Patient patient = buildPatient(UUID.randomUUID());

        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> patientService.confirmLinkUserAccount(
                "123",
                "999999",
                "Pass123!"
        )).isInstanceOf(PatientAlreadyLinkedUserException.class);

        verify(verificationModuleApi, never()).verifyCode(any(), any(), any());
        verifyNoInteractions(userModuleApi);
    }

    @Test
    void findByIdShouldReturnPatientWhenExists() {
        UUID id = UUID.randomUUID();
        Patient patient = buildPatient(null);

        when(patientRepository.findById(id)).thenReturn(Optional.of(patient));

        Optional<PatientData> result = patientService.findById(id);

        assertThat(result).isPresent();
    }

    @Test
    void findByIdShouldThrowWhenIdNull() {
        assertThatThrownBy(() -> patientService.findById(null))
                .isInstanceOf(InvalidPatientDataException.class);
    }

    @Test
    void existsByIdShouldReturnTrue() {
        UUID id = UUID.randomUUID();
        when(patientRepository.existsById(id)).thenReturn(true);

        boolean result = patientService.existsById(id);

        assertThat(result).isTrue();
    }

    @Test
    void existsByIdShouldThrowWhenIdNull() {
        assertThatThrownBy(() -> patientService.existsById(null))
                .isInstanceOf(InvalidPatientDataException.class);
    }

    private Patient buildPatient(UUID userId) {
        return new Patient(
                co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType.CEDULA,
                "123",
                "Juan",
                "Perez",
                "300",
                "mail@test.com",
                co.edu.unicauca.piedrazul.backend.patients.domain.Gender.MASCULINO,
                LocalDate.of(2000, 1, 1),
                "111",
                userId
        );
    }


}