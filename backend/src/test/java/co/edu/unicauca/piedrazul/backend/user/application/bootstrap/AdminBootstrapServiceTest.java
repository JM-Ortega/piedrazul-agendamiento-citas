package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.AccountCreationResult;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.PersonRepository;
import jakarta.ws.rs.ProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.representations.idm.UserRepresentation;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static co.edu.unicauca.piedrazul.backend.user.application.bootstrap.AdminBootstrapOutcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * Propiedades del bootstrap del primer ADMIN de negocio frente a cada estado observado y
 * a cada fallo parcial entre Keycloak y PostgreSQL. Lo esencial: qué escrituras ocurren
 * (y cuáles nunca), cuándo se compensa y qué resultado se informa.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminBootstrapServiceTest {

    private static final String USERNAME = "1061234567";
    private static final String EMAIL = "ana.prueba@example.test";
    private static final String PASSWORD = "Temp-Bootstrap-Value-2026!";
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID PERSON = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock
    private AdminBootstrapInputValidator inputValidator;
    @Mock
    private KeycloakUserClient keycloak;
    @Mock
    private PersonExternalService personService;
    @Mock
    private PersonRepository personRepository;

    private FakeLock lock;
    private AdminBootstrapService service;

    @BeforeEach
    void setUp() {
        lock = new FakeLock();
        service = new AdminBootstrapService(inputValidator, lock, keycloak, personService, personRepository);
        when(inputValidator.invalidInputs(any())).thenReturn(List.of());
        cleanState();
    }

    private static AdminBootstrapRequest request() {
        // Correo con mayúsculas: el bootstrap lo normaliza antes de consultar y escribir
        return new AdminBootstrapRequest(USERNAME, "CEDULA", "Ana", "Prueba", "Ana.Prueba@Example.TEST", "3000000000", PASSWORD);
    }

    private void cleanState() {
        when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.empty());
        when(personRepository.findByIdentification(USERNAME)).thenReturn(Optional.empty());
        when(keycloak.findUserByEmail(EMAIL)).thenReturn(Optional.empty());
        when(personRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(false);
        when(keycloak.findUsersByRole(Role.ADMIN)).thenReturn(List.of());
    }

    /** Creación que llega hasta la Person: la cuenta nueva queda desactivada con sus prerrequisitos. */
    private void creationSucceedsUpToPerson() {
        when(keycloak.createDisabledUserWithTemporaryPassword(USERNAME, "Ana", "Prueba", EMAIL, PASSWORD))
                .thenReturn(new AccountCreationResult.Created(ACCOUNT));
        when(personService.createPerson(IdentificationType.CEDULA, USERNAME, "Ana", "Prueba", "3000000000", EMAIL, ACCOUNT))
                .thenReturn(new PersonSummary(PERSON, ACCOUNT, IdentificationType.CEDULA, USERNAME, "Ana", "Prueba", "3000000000", EMAIL));
        when(keycloak.findUserById(ACCOUNT))
                .thenReturn(Optional.of(account(ACCOUNT, false, List.of("UPDATE_PASSWORD"))))
                .thenReturn(Optional.of(account(ACCOUNT, true, List.of("UPDATE_PASSWORD"))));
        when(keycloak.getEffectiveRealmRoles(ACCOUNT)).thenReturn(List.of("ADMIN", "default-roles-piedrazul"));
        when(keycloak.hasPasswordCredential(ACCOUNT)).thenReturn(true);
        when(personRepository.findByUserId(ACCOUNT)).thenReturn(Optional.of(person(PERSON, ACCOUNT)));
    }

    private static UserRepresentation account(UUID id, boolean enabled, List<String> requiredActions) {
        UserRepresentation user = new UserRepresentation();
        user.setId(id.toString());
        user.setUsername(USERNAME);
        user.setEnabled(enabled);
        user.setEmailVerified(false);
        user.setRequiredActions(new ArrayList<>(requiredActions));
        return user;
    }

    private static Person person(UUID id, UUID userId) {
        Person person = new Person(userId, IdentificationType.CEDULA, USERNAME, "Ana", "Prueba", "3000000000", EMAIL);
        person.setId(id);
        return person;
    }

    /** Ninguna escritura de identidad: ni en Keycloak ni en Person. */
    private void verifyNoIdentityWrites() {
        verify(keycloak, never()).createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any());
        verify(keycloak, never()).createUser(any(), any(), any(), any(), any());
        verify(keycloak, never()).assignRoleIfMissing(any(), any());
        verify(keycloak, never()).revokeRoleIfPresent(any(), any());
        verify(keycloak, never()).enableUser(any());
        verify(keycloak, never()).setEnabled(any(), any(Boolean.class), any());
        verify(keycloak, never()).resetPassword(any(), any());
        verify(keycloak, never()).updateUser(any(), any(), any(), any(), any());
        verify(keycloak, never()).deleteUser(any());
        verify(personService, never()).createPerson(any(), any(), any(), any(), any(), any(), any());
        verify(personService, never()).updatePerson(any(), any(), any(), any(), any(), any(), any());
        verify(personService, never()).linkUserId(any(), any());
        verify(personService, never()).deletePerson(any());
    }

    @Nested
    class BeforeAnyIdentityOperation {

        @Test
        void invalidInputPerformsNoIdentityOperationAndDoesNotLock() {
            when(inputValidator.invalidInputs(any())).thenReturn(List.of("IDENTITY_SEED_ADMIN_PHONE"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(INVALID_INPUT);
            assertThat(result.reasons()).containsExactly("IDENTITY_SEED_ADMIN_PHONE");
            verifyNoInteractions(keycloak, personService, personRepository);
            assertThat(lock.acquisitions).isZero();
        }

        @Test
        void busyLockPerformsNoIdentityOperation() {
            lock.available = false;

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(BUSY);
            verifyNoInteractions(keycloak, personService, personRepository);
        }

        @Test
        void unavailableLockFailsWithoutChanges() {
            lock.failure = new IllegalStateException("db down");

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NO_CHANGES);
            verifyNoInteractions(keycloak, personService, personRepository);
        }

        @Test
        void missingAdminRoleInRealmFailsBeforeCreatingAnything() {
            when(keycloak.findUsersByRole(Role.ADMIN)).thenThrow(new jakarta.ws.rs.NotFoundException());

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NO_CHANGES);
            assertThat(result.reasons()).containsExactly("read_failed");
            verifyNoIdentityWrites();
        }

        @Test
        void failedObservationFailsWithoutChanges() {
            when(keycloak.findUserByUsername(USERNAME)).thenThrow(new ProcessingException("timeout"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NO_CHANGES);
            assertThat(result.reasons()).containsExactly("read_failed");
            verifyNoIdentityWrites();
            assertThat(lock.released).isTrue();
        }
    }

    @Nested
    class CleanCreation {

        @Test
        void createsDisabledAccountThenAdminThenPersonAndEnablesOnlyAfterChecks() {
            creationSucceedsUpToPerson();

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(CREATED);
            assertThat(result.keycloakUserId()).isEqualTo(ACCOUNT);
            assertThat(result.personId()).isEqualTo(PERSON);
            assertThat(result.observations())
                    .containsEntry("enabled", "true")
                    .containsEntry("admin_role", "true")
                    .containsEntry("update_password_pending", "true")
                    .containsEntry("email_verified", "false")
                    .containsEntry("login_confirmed", "no");

            InOrder order = inOrder(keycloak, personService);
            order.verify(keycloak).createDisabledUserWithTemporaryPassword(USERNAME, "Ana", "Prueba", EMAIL, PASSWORD);
            order.verify(keycloak).assignRoleIfMissing(ACCOUNT, Role.ADMIN);
            order.verify(personService).createPerson(IdentificationType.CEDULA, USERNAME, "Ana", "Prueba", "3000000000", EMAIL, ACCOUNT);
            order.verify(keycloak).enableUser(ACCOUNT);
            verify(keycloak, never()).deleteUser(any());
            verify(keycloak, never()).resetPassword(any(), any());
            assertThat(lock.released).isTrue();
        }

        @Test
        void neverUsesOrdinaryProvisioningThatAdoptsAccounts() {
            creationSucceedsUpToPerson();

            service.bootstrap(request());

            verify(keycloak, never()).createUser(any(), any(), any(), any(), any());
            verify(keycloak, never()).resetPassword(any(), any());
        }

        @Test
        void logLineCarriesNoCredentialsOrPersonalData() {
            creationSucceedsUpToPerson();

            String line = service.bootstrap(request()).toLogLine();

            assertThat(line).startsWith("identity-bootstrap result=CREATED ")
                    .doesNotContain(PASSWORD, USERNAME, EMAIL, "Ana", "Prueba", "3000000000");
        }
    }

    @Nested
    class ExistingLinkedIdentity {

        private void linked(boolean enabled, List<String> roles, List<String> requiredActions) {
            UserRepresentation existing = account(ACCOUNT, enabled, requiredActions);
            when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.of(existing));
            when(personRepository.findByIdentification(USERNAME)).thenReturn(Optional.of(person(PERSON, ACCOUNT)));
            when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.of(existing));
            when(keycloak.getEffectiveRealmRoles(ACCOUNT)).thenReturn(roles);
        }

        @Test
        void confirmedAuthorityIsReadyWithoutAnyWrite() {
            linked(true, List.of("ADMIN"), List.of());

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED_READY);
            assertThat(result.outcome().successful()).isTrue();
            assertThat(result.reasons()).isEmpty();
            assertThat(result.observations())
                    .containsEntry("enabled", "true")
                    .containsEntry("admin_role", "true")
                    .containsEntry("login_confirmed", "unknown");
            verifyNoIdentityWrites();
        }

        @Test
        void removedAdminRoleIsNotReadyAndStaysRemoved() {
            linked(true, List.of("default-roles-piedrazul"), List.of());

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED_NOT_READY);
            assertThat(result.outcome().successful()).isFalse();
            assertThat(result.reasons()).containsExactly("admin_role_missing");
            assertThat(result.observations()).containsEntry("admin_role", "false");
            verifyNoIdentityWrites();
        }

        @Test
        void disabledAccountIsNotReadyAndStaysDisabled() {
            linked(false, List.of("ADMIN"), List.of());

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED_NOT_READY);
            assertThat(result.reasons()).containsExactly("account_disabled");
            verifyNoIdentityWrites();
        }

        @Test
        void disabledAccountWithoutAdminReportsBoth() {
            linked(false, List.of(), List.of());

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED_NOT_READY);
            assertThat(result.reasons()).containsExactly("account_disabled", "admin_role_missing");
            verifyNoIdentityWrites();
        }

        @Test
        void pendingFirstLoginIsStillReadyAndNotRepaired() {
            linked(true, List.of("ADMIN"), List.of("UPDATE_PASSWORD"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED_READY);
            assertThat(result.observations()).containsEntry("update_password_pending", "true");
            verifyNoIdentityWrites();
        }

        @Test
        void failedRoleObservationIsAFailureNotALinkedState() {
            linked(true, List.of("ADMIN"), List.of());
            when(keycloak.getEffectiveRealmRoles(ACCOUNT)).thenThrow(new ProcessingException("timeout"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NO_CHANGES);
            assertThat(result.outcome().successful()).isFalse();
            assertThat(result.reasons()).containsExactly("linked_observation_failed");
            assertThat(result.observations()).doesNotContainKeys("enabled", "admin_role");
            verifyNoIdentityWrites();
        }

        @Test
        void failedAccountReadIsAFailureNotALinkedState() {
            linked(true, List.of("ADMIN"), List.of());
            when(keycloak.findUserById(ACCOUNT)).thenThrow(new ProcessingException("timeout"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NO_CHANGES);
            assertThat(result.reasons()).containsExactly("linked_observation_failed");
            verifyNoIdentityWrites();
        }

        @Test
        void accountVanishingBetweenReadsNeedsInspection() {
            linked(true, List.of("ADMIN"), List.of());
            when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.empty());

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).containsExactly("linked_account_disappeared");
            verifyNoIdentityWrites();
        }

        @Test
        void otherAdminHoldersAreNotMaskedByReady() {
            linked(true, List.of("ADMIN"), List.of());
            when(keycloak.findUsersByRole(Role.ADMIN)).thenReturn(List.of(
                    account(ACCOUNT, true, List.of()), account(OTHER_ACCOUNT, true, List.of())));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED_NOT_READY);
            assertThat(result.reasons()).containsExactly("other_admin_holders");
            assertThat(result.observations())
                    .containsEntry("other_admin_holders", "1")
                    .containsEntry("other_admin_ids", OTHER_ACCOUNT.toString());
            verifyNoIdentityWrites();
        }

        @Test
        void theLinkedAccountItselfIsNotAnotherAdminHolder() {
            linked(true, List.of("ADMIN"), List.of());
            when(keycloak.findUsersByRole(Role.ADMIN)).thenReturn(List.of(account(ACCOUNT, true, List.of())));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED_READY);
            assertThat(result.observations()).containsEntry("other_admin_holders", "0");
        }

        @Test
        void differentConfiguredDocumentTypeIsNotReady() {
            linked(true, List.of("ADMIN"), List.of());
            Person other = person(PERSON, ACCOUNT);
            other.setIdentificationType(IdentificationType.CEDULA_EXTRANJERIA);
            when(personRepository.findByIdentification(USERNAME)).thenReturn(Optional.of(other));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED_NOT_READY);
            assertThat(result.reasons()).containsExactly("configured_identification_type_mismatch");
            verifyNoIdentityWrites();
        }

        @Test
        void laterEmailOrNameChangesAreDiagnosticsOnly() {
            UserRepresentation existing = account(ACCOUNT, true, List.of());
            existing.setEmail("nuevo.correo@example.test");
            Person changed = person(PERSON, ACCOUNT);
            changed.setEmail("nuevo.correo@example.test");
            changed.setLastName("Casada");
            when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.of(existing));
            when(personRepository.findByIdentification(USERNAME)).thenReturn(Optional.of(changed));
            when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.of(existing));
            when(keycloak.getEffectiveRealmRoles(ACCOUNT)).thenReturn(List.of("ADMIN"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(ALREADY_LINKED_READY);
            assertThat(result.observations())
                    .containsEntry("configured_email_matches_account", "false")
                    .containsEntry("configured_email_matches_person", "false")
                    .containsEntry("configured_names_match_person", "false");
            assertThat(result.toLogLine()).doesNotContain("nuevo.correo", "Casada", EMAIL);
            verifyNoIdentityWrites();
        }

        @Test
        void failedAdminHolderObservationIsAFailure() {
            linked(true, List.of("ADMIN"), List.of());
            when(keycloak.findUsersByRole(Role.ADMIN)).thenThrow(new ProcessingException("timeout"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NO_CHANGES);
            assertThat(result.reasons()).containsExactly("linked_observation_failed");
            verifyNoIdentityWrites();
        }
    }

    @Nested
    class Conflicts {

        private AdminBootstrapResult bootstrapExpectingConflict() {
            AdminBootstrapResult result = service.bootstrap(request());
            assertThat(result.outcome()).isEqualTo(CONFLICT);
            verifyNoIdentityWrites();
            return result;
        }

        @Test
        void existingKeycloakAccountWithoutPersonIsNotAdopted() {
            when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.of(account(ACCOUNT, true, List.of())));
            when(personRepository.findByUserId(ACCOUNT)).thenReturn(Optional.empty());

            assertThat(bootstrapExpectingConflict().reasons()).containsExactly("keycloak_account_without_person");
        }

        @Test
        void existingPersonWithoutAccountIsNotAdopted() {
            when(personRepository.findByIdentification(USERNAME)).thenReturn(Optional.of(person(PERSON, null)));

            assertThat(bootstrapExpectingConflict().reasons()).containsExactly("person_exists_without_account");
        }

        @Test
        void personLinkedToAMissingKeycloakAccountIsReported() {
            when(personRepository.findByIdentification(USERNAME)).thenReturn(Optional.of(person(PERSON, OTHER_ACCOUNT)));
            when(keycloak.findUserById(OTHER_ACCOUNT)).thenReturn(Optional.empty());

            assertThat(bootstrapExpectingConflict().reasons()).containsExactly("person_linked_to_missing_account");
        }

        @Test
        void mismatchedLinkageIsReported() {
            when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.of(account(ACCOUNT, true, List.of())));
            when(personRepository.findByIdentification(USERNAME)).thenReturn(Optional.of(person(PERSON, OTHER_ACCOUNT)));

            assertThat(bootstrapExpectingConflict().reasons()).containsExactly("person_linked_to_other_account");
        }

        @Test
        void accountLinkedToAnotherPersonIsReported() {
            when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.of(account(ACCOUNT, true, List.of())));
            when(personRepository.findByUserId(ACCOUNT)).thenReturn(Optional.of(person(PERSON, ACCOUNT)));

            assertThat(bootstrapExpectingConflict().reasons()).containsExactly("account_linked_to_other_person");
        }

        @Test
        void emailUsedByAnotherKeycloakAccountIsAConflict() {
            when(keycloak.findUserByEmail(EMAIL)).thenReturn(Optional.of(account(OTHER_ACCOUNT, true, List.of())));

            assertThat(bootstrapExpectingConflict().reasons()).containsExactly("email_in_use_by_other_account");
        }

        @Test
        void emailUsedByAnotherPersonIsAConflict() {
            when(personRepository.existsByEmailIgnoreCase(EMAIL)).thenReturn(true);

            assertThat(bootstrapExpectingConflict().reasons()).containsExactly("email_in_use_by_person");
        }

        @Test
        void existingAdminAuthorityBlocksCreatingAnotherOne() {
            when(keycloak.findUsersByRole(Role.ADMIN)).thenReturn(List.of(account(OTHER_ACCOUNT, true, List.of())));

            assertThat(bootstrapExpectingConflict().reasons()).containsExactly("existing_admin_authority");
        }

        @Test
        void raceLostAtCreationIsAConflictAndDeletesNothing() {
            when(keycloak.createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any()))
                    .thenReturn(new AccountCreationResult.Conflict());

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(CONFLICT);
            assertThat(result.reasons()).containsExactly("keycloak_create_conflict");
            verify(keycloak, never()).assignRoleIfMissing(any(), any());
            verify(keycloak, never()).deleteUser(any());
            verify(personService, never()).createPerson(any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    class AccountCreationFailures {

        @Test
        void rejectedCreationFailsWithoutChanges() {
            when(keycloak.createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any()))
                    .thenReturn(new AccountCreationResult.Rejected(400));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NO_CHANGES);
            verify(keycloak, never()).deleteUser(any());
            verify(keycloak, never()).assignRoleIfMissing(any(), any());
        }

        @Test
        void uncertainCreationAssumesNoOwnershipAndTouchesNothing() {
            when(keycloak.createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any()))
                    .thenReturn(new AccountCreationResult.Uncertain("ProcessingException"));
            // La relectura encuentra una cuenta: aun así no se puede probar que sea de este intento
            when(keycloak.findUserByUsername(USERNAME))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(account(ACCOUNT, false, List.of("UPDATE_PASSWORD"))));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).containsExactly("create_outcome_uncertain");
            assertThat(result.observations()).containsEntry("account_found", "true");
            verify(keycloak, never()).deleteUser(any());
            verify(keycloak, never()).assignRoleIfMissing(any(), any());
            verify(keycloak, never()).enableUser(any());
            verify(personService, never()).createPerson(any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    class FailuresBeforePersonPersistence {

        @BeforeEach
        void created() {
            when(keycloak.createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any()))
                    .thenReturn(new AccountCreationResult.Created(ACCOUNT));
        }

        @Test
        void roleFailureDeletesOnlyTheOwnedDisabledUnlinkedAccount() {
            doThrow(new ProcessingException("timeout")).when(keycloak).assignRoleIfMissing(ACCOUNT, Role.ADMIN);
            when(keycloak.findUserById(ACCOUNT))
                    .thenReturn(Optional.of(account(ACCOUNT, false, List.of("UPDATE_PASSWORD"))))
                    .thenReturn(Optional.empty());
            when(personRepository.existsByUserId(ACCOUNT)).thenReturn(false);

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_COMPENSATED);
            assertThat(result.reasons()).containsExactly("assign_admin_role_failed");
            verify(keycloak).deleteUser(ACCOUNT);
            verify(personService, never()).createPerson(any(), any(), any(), any(), any(), any(), any());
            verify(keycloak, never()).enableUser(any());
        }

        @Test
        void enabledAccountIsNeverCompensated() {
            doThrow(new ProcessingException("timeout")).when(keycloak).assignRoleIfMissing(ACCOUNT, Role.ADMIN);
            when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.of(account(ACCOUNT, true, List.of())));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).contains("compensation_unsafe_account_enabled");
            verify(keycloak, never()).deleteUser(any());
        }

        @Test
        void accountWithLinkedPersonIsNeverCompensated() {
            doThrow(new ProcessingException("timeout")).when(keycloak).assignRoleIfMissing(ACCOUNT, Role.ADMIN);
            when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.of(account(ACCOUNT, false, List.of())));
            when(personRepository.existsByUserId(ACCOUNT)).thenReturn(true);

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).contains("compensation_unsafe_person_linked");
            verify(keycloak, never()).deleteUser(any());
        }

        @Test
        void failedCompensationIsReportedNotAcceptedAsSuccess() {
            doThrow(new ProcessingException("timeout")).when(keycloak).assignRoleIfMissing(ACCOUNT, Role.ADMIN);
            when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.of(account(ACCOUNT, false, List.of())));
            doThrow(new ProcessingException("timeout")).when(keycloak).deleteUser(ACCOUNT);

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).contains("compensation_failed");
        }

        @Test
        void deleteErrorWithConfirmedAbsenceCountsAsCompensated() {
            doThrow(new ProcessingException("timeout")).when(keycloak).assignRoleIfMissing(ACCOUNT, Role.ADMIN);
            when(keycloak.findUserById(ACCOUNT))
                    .thenReturn(Optional.of(account(ACCOUNT, false, List.of())))
                    .thenReturn(Optional.empty());
            doThrow(new ProcessingException("timeout")).when(keycloak).deleteUser(ACCOUNT);

            assertThat(service.bootstrap(request()).outcome()).isEqualTo(FAILED_COMPENSATED);
        }

        @Test
        void unverifiableCompensationPreconditionsDeleteNothing() {
            doThrow(new ProcessingException("timeout")).when(keycloak).assignRoleIfMissing(ACCOUNT, Role.ADMIN);
            when(keycloak.findUserById(ACCOUNT)).thenThrow(new ProcessingException("timeout"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).contains("compensation_check_failed");
            verify(keycloak, never()).deleteUser(any());
        }

        @Test
        void definitePersonRejectionCompensatesTheOwnedAccount() {
            when(personService.createPerson(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new DataIntegrityViolationException("uq_person_identification"));
            when(keycloak.findUserById(ACCOUNT))
                    .thenReturn(Optional.of(account(ACCOUNT, false, List.of("UPDATE_PASSWORD"))))
                    .thenReturn(Optional.empty());

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_COMPENSATED);
            assertThat(result.reasons()).containsExactly("persist_person_failed");
            verify(keycloak).deleteUser(ACCOUNT);
            verify(keycloak, never()).enableUser(any());
        }

        @Test
        void concurrentPersonDetectedBeforeWriteCompensatesTheOwnedAccount() {
            when(personService.createPerson(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new PersonAlreadyExistsException("Ya existe"));
            when(keycloak.findUserById(ACCOUNT))
                    .thenReturn(Optional.of(account(ACCOUNT, false, List.of())))
                    .thenReturn(Optional.empty());

            assertThat(service.bootstrap(request()).outcome()).isEqualTo(FAILED_COMPENSATED);
            verify(keycloak).deleteUser(ACCOUNT);
        }
    }

    @Nested
    class FailuresAfterPersonMayHaveCommitted {

        @Test
        void ambiguousPersonPersistenceNeverDeletes() {
            when(keycloak.createDisabledUserWithTemporaryPassword(any(), any(), any(), any(), any()))
                    .thenReturn(new AccountCreationResult.Created(ACCOUNT));
            when(personService.createPerson(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new TransactionSystemException("commit failed"));
            // Una relectura negativa no autoriza borrar
            when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.of(account(ACCOUNT, false, List.of())));
            when(personRepository.existsByUserId(ACCOUNT)).thenReturn(false);

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).containsExactly("person_outcome_uncertain");
            assertThat(result.keycloakUserId()).isEqualTo(ACCOUNT);
            verify(keycloak, never()).deleteUser(any());
            verify(keycloak, never()).enableUser(any());
        }

        @Test
        void unmetPrerequisitesBlockEnablementAndDeleteNothing() {
            creationSucceedsUpToPerson();
            when(keycloak.getEffectiveRealmRoles(ACCOUNT)).thenReturn(List.of("default-roles-piedrazul"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.stage()).isEqualTo("pre_enable_check");
            assertThat(result.reasons()).containsExactly("admin_role_missing");
            verify(keycloak, never()).enableUser(any());
            verify(keycloak, never()).deleteUser(any());
            verify(personService, never()).deletePerson(any());
        }

        @Test
        void missingUpdatePasswordBlocksEnablement() {
            creationSucceedsUpToPerson();
            when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.of(account(ACCOUNT, false, List.of())));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.reasons()).containsExactly("update_password_not_required");
            verify(keycloak, never()).enableUser(any());
        }

        @Test
        void enablementFailureNeverDeletesTheLinkedIdentity() {
            creationSucceedsUpToPerson();
            doThrow(new ProcessingException("timeout")).when(keycloak).enableUser(ACCOUNT);

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).containsExactly("enable_failed_or_uncertain");
            verify(keycloak, never()).deleteUser(any());
            verify(personService, never()).deletePerson(any());
        }

        @Test
        void finalReadbackFailureNeverDeletes() {
            creationSucceedsUpToPerson();
            when(keycloak.findUserById(ACCOUNT))
                    .thenReturn(Optional.of(account(ACCOUNT, false, List.of("UPDATE_PASSWORD"))))
                    .thenThrow(new ProcessingException("timeout"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).containsExactly("final_readback_failed");
            verify(keycloak, never()).deleteUser(any());
        }

        @Test
        void finalReadbackMismatchIsNotReportedAsCreated() {
            creationSucceedsUpToPerson();
            when(keycloak.findUserById(ACCOUNT))
                    .thenReturn(Optional.of(account(ACCOUNT, false, List.of("UPDATE_PASSWORD"))))
                    .thenReturn(Optional.of(account(ACCOUNT, false, List.of("UPDATE_PASSWORD"))));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            assertThat(result.reasons()).containsExactly("account_not_enabled");
            verify(keycloak, never()).deleteUser(any());
        }

        @Test
        void unexpectedErrorAfterWritesIsNeverAssumedHarmless() {
            creationSucceedsUpToPerson();
            when(personRepository.findByUserId(ACCOUNT)).thenThrow(new IllegalStateException("boom"));

            AdminBootstrapResult result = service.bootstrap(request());

            assertThat(result.outcome()).isEqualTo(FAILED_NEEDS_INSPECTION);
            verify(keycloak, never()).deleteUser(any());
            assertThat(lock.released).isTrue();
        }
    }

    @Test
    void secondAttemptAfterCreationReportsLinkedAndWritesNothing() {
        creationSucceedsUpToPerson();
        assertThat(service.bootstrap(request()).outcome()).isEqualTo(CREATED);

        // Estado que dejó el primer intento
        UserRepresentation created = account(ACCOUNT, true, List.of("UPDATE_PASSWORD"));
        when(keycloak.findUserByUsername(USERNAME)).thenReturn(Optional.of(created));
        when(personRepository.findByIdentification(USERNAME)).thenReturn(Optional.of(person(PERSON, ACCOUNT)));
        when(keycloak.findUserById(ACCOUNT)).thenReturn(Optional.of(created));
        org.mockito.Mockito.clearInvocations(keycloak, personService);

        AdminBootstrapResult second = service.bootstrap(request());

        assertThat(second.outcome()).isEqualTo(ALREADY_LINKED_READY);
        verifyNoIdentityWrites();
    }

    private static final class FakeLock implements AdminBootstrapLock {
        boolean available = true;
        RuntimeException failure;
        int acquisitions;
        boolean released;

        @Override
        public Optional<Held> tryAcquire() {
            acquisitions++;
            if (failure != null) {
                throw failure;
            }
            if (!available) {
                return Optional.empty();
            }
            return Optional.of(() -> released = true);
        }
    }
}
