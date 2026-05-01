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
    void createPatientWithUserShouldCreateUserAndPatient() {
        UUID userId = UUID.randomUUID();
        Patient patient = buildPatient(userId);

        when(patientRepository.existsByDocumentNumber("123")).thenReturn(false);
        when(userModuleApi.getOrCreatePatientUser("juan", "Juan", "Perez", "mail@test.com", "Pass123!"))
                .thenReturn(userId);
        when(patientRepository.save(any())).thenReturn(patient);

        PatientData result = patientService.createPatientWithUser(
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
        );

        assertThat(result).isNotNull();
        verify(userModuleApi).getOrCreatePatientUser("juan", "Juan", "Perez", "mail@test.com", "Pass123!");
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
        )).isInstanceOf(InvalidPatientDataException.class);

        verifyNoInteractions(userModuleApi);
    }

    @Test
    void linkUserToExistingPatientShouldLinkCorrectly() {
        UUID userId = UUID.randomUUID();
        Patient patient = buildPatient(null);

        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.of(patient));
        when(userModuleApi.getOrCreatePatientUser("juan", "Juan", "Perez", "mail@test.com", "Pass123!"))
                .thenReturn(userId);
        when(patientRepository.save(any())).thenReturn(patient);

        PatientData result = patientService.linkUserToExistingPatient(
                "123", "juan", "Juan", "Perez", "mail@test.com", "Pass123!"
        );

        assertThat(result).isNotNull();
        assertThat(patient.getUserId()).isEqualTo(userId);
        verify(patientRepository).save(patient);
    }

    @Test
    void linkUserToExistingPatientShouldThrowWhenPatientNotFound() {
        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                patientService.linkUserToExistingPatient(
                        "123", "juan", "Juan", "Perez", "mail@test.com", "Pass123!"
                )).isInstanceOf(PatientNotFoundException.class);
    }

    @Test
    void linkUserToExistingPatientShouldThrowWhenAlreadyLinked() {
        Patient patient = buildPatient(UUID.randomUUID());

        when(patientRepository.findByDocumentNumber("123")).thenReturn(Optional.of(patient));

        assertThatThrownBy(() ->
                patientService.linkUserToExistingPatient(
                        "123", "juan", "Juan", "Perez", "mail@test.com", "Pass123!"
                )).isInstanceOf(PatientAlreadyLinkedUserException.class);

        verify(userModuleApi, never()).getOrCreatePatientUser(any(), any(), any(), any(), any());
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