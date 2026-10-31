package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientPublicResponse;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientServicePublicLookupTest {

    private static final String DOC = "1061234567";
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

    @InjectMocks
    private PatientService service;

    private PersonSummary person(UUID linkedUserId) {
        return new PersonSummary(
                personId, linkedUserId, IdentificationType.CEDULA, DOC,
                "Ana", "Ruiz", "3001234567", "ana@example.com");
    }

    private void givenPerson(UUID linkedUserId) {
        when(personExternalService.findByIdentification(DOC)).thenReturn(Optional.of(person(linkedUserId)));
    }

    private void givenNoPerson() {
        when(personExternalService.findByIdentification(DOC)).thenReturn(Optional.empty());
    }

    private void givenPatientExists(boolean exists) {
        when(patientRepository.findById(personId)).thenReturn(
                exists ? Optional.of(new Patient(personId, Sex.FEMENINO, ADULT, null)) : Optional.empty());
    }

    private void givenSystemUser(boolean exists) {
        when(userModuleApi.findUserByUsername(DOC)).thenReturn(
                exists
                        ? Optional.of(new UserSummary(userId, DOC, "Ana", "Ruiz", "ana@example.com", List.of()))
                        : Optional.empty());
    }

    private void givenPatientRole(boolean granted) {
        when(userModuleApi.getUserRoles(userId)).thenReturn(
                granted ? List.of(Role.PATIENT.name()) : List.of(Role.DOCTOR.name()));
    }

    @Test
    void stateA_shouldFailWhenNothingExists() {
        givenNoPerson();
        givenSystemUser(false);

        assertThrows(PatientNotFoundException.class, () -> service.findPublicByDocumentNumber(DOC));
    }

    @Test
    void stateB_shouldReportPatientWithoutAccountAndSkipRoleLookup() {
        givenPerson(null);
        givenPatientExists(true);
        givenSystemUser(false);

        PatientPublicResponse response = service.findPublicByDocumentNumber(DOC);

        assertAll(
                () -> assertTrue(response.patientExists()),
                () -> assertFalse(response.hasUserAccount()),
                () -> assertFalse(response.hasSystemUser()),
                () -> assertFalse(response.hasPatientRole()),
                () -> assertEquals("Ana", response.firstName())
        );

        verify(userModuleApi, never()).getUserRoles(any());
    }

    @Test
    void stateC1_shouldReportAccountWithoutPatientAndWithoutRole() {
        givenPerson(userId);
        givenPatientExists(false);
        givenSystemUser(true);
        givenPatientRole(false);

        PatientPublicResponse response = service.findPublicByDocumentNumber(DOC);

        assertAll(
                () -> assertFalse(response.patientExists()),
                () -> assertTrue(response.hasUserAccount()),
                () -> assertTrue(response.hasSystemUser()),
                () -> assertFalse(response.hasPatientRole()),
                () -> assertEquals("Ana", response.firstName())
        );

        verify(userModuleApi).getUserRoles(userId);
    }

    // Simula el rol de paciente asignado externamente antes de existir Patient.
    @Test
    void stateC2_shouldReportAccountWithoutPatientButWithRoleAlreadyGranted() {
        givenPerson(userId);
        givenPatientExists(false);
        givenSystemUser(true);
        givenPatientRole(true);

        PatientPublicResponse response = service.findPublicByDocumentNumber(DOC);

        assertAll(
                () -> assertFalse(response.patientExists()),
                () -> assertTrue(response.hasUserAccount()),
                () -> assertTrue(response.hasSystemUser()),
                () -> assertTrue(response.hasPatientRole()),
                () -> assertEquals("Ana", response.firstName())
        );

        verify(userModuleApi).getUserRoles(userId);
    }

    @Test
    void stateD_shouldReportPatientWithAccountButWithoutPortalAccess() {
        givenPerson(userId);
        givenPatientExists(true);
        givenSystemUser(true);
        givenPatientRole(false);

        PatientPublicResponse response = service.findPublicByDocumentNumber(DOC);

        assertAll(
                () -> assertTrue(response.patientExists()),
                () -> assertTrue(response.hasUserAccount()),
                () -> assertTrue(response.hasSystemUser()),
                () -> assertFalse(response.hasPatientRole())
        );

        verify(userModuleApi).getUserRoles(userId);
    }

    @Test
    void stateE_shouldReportFullyEnabledPatient() {
        givenPerson(userId);
        givenPatientExists(true);
        givenSystemUser(true);
        givenPatientRole(true);

        PatientPublicResponse response = service.findPublicByDocumentNumber(DOC);

        assertAll(
                () -> assertTrue(response.patientExists()),
                () -> assertTrue(response.hasUserAccount()),
                () -> assertTrue(response.hasSystemUser()),
                () -> assertTrue(response.hasPatientRole())
        );
    }

    @Test
    void stateF_shouldReportEverythingMissingForTheAnomalousState() {
        givenPerson(null);
        givenPatientExists(false);
        givenSystemUser(false);

        PatientPublicResponse response = service.findPublicByDocumentNumber(DOC);

        assertAll(
                () -> assertFalse(response.patientExists()),
                () -> assertFalse(response.hasUserAccount()),
                () -> assertFalse(response.hasSystemUser()),
                () -> assertFalse(response.hasPatientRole())
        );

        verify(userModuleApi, never()).getUserRoles(any());
    }

    @Test
    void keycloakOnly_shouldBeDistinguishableFromAnAccountLinkedToAPerson() {
        givenNoPerson();
        givenSystemUser(true);

        PatientPublicResponse response = service.findPublicByDocumentNumber(DOC);

        assertAll(
                () -> assertFalse(response.patientExists()),
                () -> assertFalse(response.hasUserAccount()),
                () -> assertTrue(response.hasSystemUser()),
                () -> assertFalse(response.hasPatientRole())
        );

        verify(userModuleApi, never()).getUserRoles(any());
    }
}
