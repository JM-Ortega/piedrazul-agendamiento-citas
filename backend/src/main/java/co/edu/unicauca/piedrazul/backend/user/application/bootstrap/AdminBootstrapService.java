package co.edu.unicauca.piedrazul.backend.user.application.bootstrap;

import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.domain.Person;
import co.edu.unicauca.piedrazul.backend.user.exception.InvalidUserDataException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyLinkedUserException;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.AccountCreationResult;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.KeycloakUserClient;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.PersonRepository;
import jakarta.validation.ConstraintViolationException;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static co.edu.unicauca.piedrazul.backend.user.application.bootstrap.AdminBootstrapOutcome.*;

/**
 * Bootstrap create-only del primer administrador de negocio (rol {@code ADMIN} del realm
 * de la aplicación; no es autoridad de la plataforma Keycloak).
 *
 * <p>Orden: validar entradas → bloqueo → clasificar (solo lecturas) → si y solo si no
 * existe nada que adoptar: crear la cuenta desactivada con contraseña temporal y
 * {@code UPDATE_PASSWORD} → asignar ADMIN → persistir la Person vinculada → comprobar
 * prerrequisitos → activar → releer.
 *
 * <p>Keycloak y PostgreSQL no comparten transacción. Reglas de efectos:
 * <ul>
 *   <li>Una identidad ya vinculada no recibe ninguna escritura (ni roles, ni activación,
 *   ni credenciales, ni datos), aunque le falte ADMIN o esté desactivada: se informa como
 *   {@code ALREADY_LINKED_NOT_READY}, no como lista.</li>
 *   <li>Cualquier otra identidad existente, un correo en uso o una autoridad ADMIN ya
 *   presente es un conflicto: no se adopta ni se modifica.</li>
 *   <li>Solo se compensa (se borra) la cuenta que creó este intento —probado por el id que
 *   devolvió Keycloak—, desactivada y sin Person vinculada, cuando la Person no llegó a
 *   persistirse con certeza. Una creación incierta, una Person posiblemente confirmada o
 *   un fallo al activar/releer nunca provocan borrados.</li>
 * </ul>
 */
@Service
public class AdminBootstrapService {

    private final AdminBootstrapInputValidator inputValidator;
    private final AdminBootstrapLock bootstrapLock;
    private final KeycloakUserClient keycloak;
    private final PersonExternalService personService;
    private final PersonRepository personRepository;

    public AdminBootstrapService(
            AdminBootstrapInputValidator inputValidator,
            AdminBootstrapLock bootstrapLock,
            KeycloakUserClient keycloak,
            PersonExternalService personService,
            PersonRepository personRepository
    ) {
        this.inputValidator = inputValidator;
        this.bootstrapLock = bootstrapLock;
        this.keycloak = keycloak;
        this.personService = personService;
        this.personRepository = personRepository;
    }

    public AdminBootstrapResult bootstrap(AdminBootstrapRequest rawRequest) {
        String attempt = UUID.randomUUID().toString();
        AdminBootstrapRequest request = rawRequest.normalized();

        List<String> invalid = inputValidator.invalidInputs(request);
        if (!invalid.isEmpty()) {
            return AdminBootstrapResult.builder(attempt, "validate").reasons(invalid).build(INVALID_INPUT);
        }

        Optional<AdminBootstrapLock.Held> lock;
        try {
            lock = bootstrapLock.tryAcquire();
        } catch (RuntimeException ex) {
            return AdminBootstrapResult.builder(attempt, "lock")
                    .reason("lock_unavailable").cause(ex).build(FAILED_NO_CHANGES);
        }

        if (lock.isEmpty()) {
            return AdminBootstrapResult.builder(attempt, "lock")
                    .reason("bootstrap_in_progress_elsewhere").build(BUSY);
        }

        try (AdminBootstrapLock.Held ignored = lock.get()) {
            return bootstrapLocked(attempt, request);
        } catch (RuntimeException unexpected) {
            // Cada paso maneja sus propios fallos; esto solo cubre lo imprevisto, que pudo
            // ocurrir después de escribir: nunca se supone que no hubo efectos.
            return AdminBootstrapResult.builder(attempt, "unexpected")
                    .reason("unexpected_error").cause(unexpected).build(FAILED_NEEDS_INSPECTION);
        }
    }

    private AdminBootstrapResult bootstrapLocked(String attempt, AdminBootstrapRequest request) {
        Observed observed;
        try {
            observed = observe(request);
        } catch (RuntimeException ex) {
            return AdminBootstrapResult.builder(attempt, "observe")
                    .reason("read_failed").cause(ex).build(FAILED_NO_CHANGES);
        }

        if (observed.linked()) {
            return reportLinked(attempt, request, observed);
        }

        List<String> conflicts = observed.conflicts();
        if (!conflicts.isEmpty()) {
            AdminBootstrapResult.Builder result = AdminBootstrapResult.builder(attempt, "observe").reasons(conflicts);
            observed.account().ifPresent(account -> result.account(idOf(account)));
            observed.person().ifPresent(person -> result.person(person.getId()));
            return result.build(CONFLICT);
        }

        return create(attempt, request);
    }

    // ── Clasificación (solo lecturas) ──────────────────────────────────────

    private Observed observe(AdminBootstrapRequest request) {
        Optional<UserRepresentation> account = keycloak.findUserByUsername(request.username())
                .filter(user -> request.username().equalsIgnoreCase(user.getUsername()));
        Optional<Person> person = personRepository.findByIdentification(request.username());

        if (account.isPresent() && person.isPresent()
                && idOf(account.get()).equals(person.get().getUserId())) {
            return Observed.linked(account.get(), person.get());
        }

        Optional<UserRepresentation> accountWithEmail = keycloak.findUserByEmail(request.email());
        boolean personWithEmail = personRepository.existsByEmailIgnoreCase(request.email());
        Optional<Person> personOfAccount = account.flatMap(user -> personRepository.findByUserId(idOf(user)));
        Optional<Boolean> linkedAccountExists = person
                .map(Person::getUserId)
                .map(userId -> keycloak.findUserById(userId).isPresent());
        boolean adminAuthorityExists = !keycloak.findUsersByRole(Role.ADMIN).isEmpty();

        return new Observed(account, person, accountWithEmail, personWithEmail, personOfAccount,
                linkedAccountExists, adminAuthorityExists, false);
    }

    /**
     * Identidad ya vinculada: solo lecturas, ninguna escritura. {@code ALREADY_LINKED_READY}
     * exige confirmar el estado del bootstrap: cuenta activa, ADMIN efectivo, ningún otro
     * titular directo de ADMIN y el mismo tipo de documento configurado. Si algo de eso no se
     * cumple es {@code ALREADY_LINKED_NOT_READY} (reasons): puede ser una decisión
     * administrativa posterior (retirar ADMIN, desactivar, crear otros administradores), así que
     * no se repara ni se presume; decide una persona. Si no se puede observar, es un fallo.
     *
     * <p>Diferencias de correo o nombres se informan como booleanos (pueden cambiar
     * legítimamente después del primer login) y no cambian el resultado.
     */
    private AdminBootstrapResult reportLinked(String attempt, AdminBootstrapRequest request, Observed observed) {
        UUID accountId = idOf(observed.account().orElseThrow());
        Person person = observed.person().orElseThrow();
        AdminBootstrapResult.Builder result = AdminBootstrapResult.builder(attempt, "observe_linked")
                .account(accountId)
                .person(person.getId())
                .observe("login_confirmed", "unknown");

        UserRepresentation current;
        boolean admin;
        List<String> otherAdmins;
        try {
            current = keycloak.findUserById(accountId).orElse(null);
            if (current == null) {
                return result.reason("linked_account_disappeared").build(FAILED_NEEDS_INSPECTION);
            }
            admin = keycloak.getEffectiveRealmRoles(accountId).contains(Role.ADMIN.name());
            otherAdmins = keycloak.findUsersByRole(Role.ADMIN).stream()
                    .map(UserRepresentation::getId)
                    .filter(id -> !accountId.toString().equals(id))
                    .sorted()
                    .toList();
        } catch (RuntimeException ex) {
            return result.reason("linked_observation_failed").cause(ex).build(FAILED_NO_CHANGES);
        }

        boolean enabled = Boolean.TRUE.equals(current.isEnabled());
        boolean sameDocumentType = person.getIdentificationType() != null
                && person.getIdentificationType().name().equals(request.identificationType());
        result.observe("enabled", enabled)
                .observe("admin_role", admin)
                .observe("other_admin_holders", otherAdmins.size())
                .observe("update_password_pending", requiresUpdatePassword(current))
                .observe("email_verified", Boolean.TRUE.equals(current.isEmailVerified()))
                .observe("configured_email_matches_account", request.email().equalsIgnoreCase(String.valueOf(current.getEmail())))
                .observe("configured_email_matches_person", request.email().equalsIgnoreCase(String.valueOf(person.getEmail())))
                .observe("configured_names_match_person", request.firstName().equals(person.getFirstName())
                        && request.lastName().equals(person.getLastName()));
        if (!otherAdmins.isEmpty()) {
            // Ids internos de Keycloak (no datos personales), para la inspección; como mucho 5
            result.observe("other_admin_ids", String.join(",", otherAdmins.subList(0, Math.min(5, otherAdmins.size()))));
        }

        if (!enabled) {
            result.reason("account_disabled");
        }
        if (!admin) {
            result.reason("admin_role_missing");
        }
        if (!otherAdmins.isEmpty()) {
            result.reason("other_admin_holders");
        }
        if (!sameDocumentType) {
            result.reason("configured_identification_type_mismatch");
        }

        boolean ready = enabled && admin && otherAdmins.isEmpty() && sameDocumentType;
        return result.build(ready ? ALREADY_LINKED_READY : ALREADY_LINKED_NOT_READY);
    }

    // ── Creación ───────────────────────────────────────────────────────────

    private AdminBootstrapResult create(String attempt, AdminBootstrapRequest request) {
        AccountCreationResult creation = keycloak.createDisabledUserWithTemporaryPassword(
                request.username(),
                request.firstName(),
                request.lastName(),
                request.email(),
                request.temporaryPassword());

        return switch (creation) {
            case AccountCreationResult.Created created -> continueWithOwnedAccount(attempt, request, created.userId());
            case AccountCreationResult.Conflict conflict -> AdminBootstrapResult.builder(attempt, "create_account")
                    .reason("keycloak_create_conflict").build(CONFLICT);
            case AccountCreationResult.Rejected rejected -> AdminBootstrapResult.builder(attempt, "create_account")
                    .reason("keycloak_rejected_create").observe("status", rejected.status()).build(FAILED_NO_CHANGES);
            case AccountCreationResult.Uncertain uncertain -> reportUncertainCreation(attempt, request, uncertain);
        };
    }

    /** Sin el id de Keycloak no hay prueba de propiedad: se informa y no se toca nada. */
    private AdminBootstrapResult reportUncertainCreation(
            String attempt, AdminBootstrapRequest request, AccountCreationResult.Uncertain uncertain) {
        AdminBootstrapResult.Builder result = AdminBootstrapResult.builder(attempt, "create_account")
                .reason("create_outcome_uncertain")
                .observe("cause", uncertain.cause());
        try {
            Optional<UserRepresentation> found = keycloak.findUserByUsername(request.username())
                    .filter(user -> request.username().equalsIgnoreCase(user.getUsername()));
            result.observe("account_found", found.isPresent());
            found.ifPresent(user -> result.account(idOf(user)));
        } catch (RuntimeException ex) {
            result.observe("account_found", "unknown");
        }
        return result.build(FAILED_NEEDS_INSPECTION);
    }

    private AdminBootstrapResult continueWithOwnedAccount(String attempt, AdminBootstrapRequest request, UUID accountId) {
        try {
            keycloak.assignRoleIfMissing(accountId, Role.ADMIN);
        } catch (RuntimeException ex) {
            return compensate(attempt, accountId, "assign_admin_role", ex);
        }

        PersonSummary person;
        try {
            person = personService.createPerson(
                    IdentificationType.valueOf(request.identificationType()),
                    request.username(),
                    request.firstName(),
                    request.lastName(),
                    request.phone(),
                    request.email(),
                    accountId);
        } catch (RuntimeException ex) {
            if (isDefinitePersonRejection(ex)) {
                return compensate(attempt, accountId, "persist_person", ex);
            }
            return AdminBootstrapResult.builder(attempt, "persist_person")
                    .account(accountId)
                    .reason("person_outcome_uncertain")
                    .cause(ex)
                    .build(FAILED_NEEDS_INSPECTION);
        }

        return enableAfterPrerequisites(attempt, accountId, person.id());
    }

    /**
     * Rechazos que prueban que la Person no se confirmó: validación o duplicado detectados
     * antes de escribir, una violación de integridad (PostgreSQL aborta la transacción) o
     * una transacción que ni siquiera empezó. Cualquier otro fallo puede haber ocurrido al
     * confirmar y se trata como incierto.
     */
    private static boolean isDefinitePersonRejection(RuntimeException ex) {
        return ex instanceof PersonAlreadyExistsException
                || ex instanceof PersonAlreadyLinkedUserException
                || ex instanceof InvalidUserDataException
                || ex instanceof DataIntegrityViolationException
                || ex instanceof ConstraintViolationException
                || ex instanceof CannotCreateTransactionException;
    }

    private AdminBootstrapResult enableAfterPrerequisites(String attempt, UUID accountId, UUID personId) {
        List<String> unmet;
        try {
            unmet = unmetPrerequisites(accountId, personId, false);
        } catch (RuntimeException ex) {
            return AdminBootstrapResult.builder(attempt, "pre_enable_check").account(accountId).person(personId)
                    .reason("pre_enable_readback_failed").cause(ex).build(FAILED_NEEDS_INSPECTION);
        }
        if (!unmet.isEmpty()) {
            return AdminBootstrapResult.builder(attempt, "pre_enable_check").account(accountId).person(personId)
                    .reasons(unmet).build(FAILED_NEEDS_INSPECTION);
        }

        try {
            keycloak.enableUser(accountId);
        } catch (RuntimeException ex) {
            return AdminBootstrapResult.builder(attempt, "enable_account").account(accountId).person(personId)
                    .reason("enable_failed_or_uncertain").cause(ex).build(FAILED_NEEDS_INSPECTION);
        }

        AdminBootstrapResult.Builder result = AdminBootstrapResult.builder(attempt, "final_readback")
                .account(accountId).person(personId);
        try {
            unmet = unmetPrerequisites(accountId, personId, true);
        } catch (RuntimeException ex) {
            return result.reason("final_readback_failed").cause(ex).build(FAILED_NEEDS_INSPECTION);
        }
        if (!unmet.isEmpty()) {
            return result.reasons(unmet).build(FAILED_NEEDS_INSPECTION);
        }

        return result
                .observe("enabled", true)
                .observe("admin_role", true)
                .observe("update_password_pending", true)
                .observe("email_verified", false)
                .observe("password_credential", true)
                .observe("person_linked", true)
                .observe("login_confirmed", "no")
                .build(CREATED);
    }

    private List<String> unmetPrerequisites(UUID accountId, UUID personId, boolean expectEnabled) {
        UserRepresentation account = keycloak.findUserById(accountId).orElse(null);
        if (account == null) {
            return List.of("account_missing");
        }

        List<String> unmet = new ArrayList<>();
        if (Boolean.TRUE.equals(account.isEnabled()) != expectEnabled) {
            unmet.add(expectEnabled ? "account_not_enabled" : "account_unexpectedly_enabled");
        }
        if (Boolean.TRUE.equals(account.isEmailVerified())) {
            unmet.add("email_unexpectedly_verified");
        }
        if (!requiresUpdatePassword(account)) {
            unmet.add("update_password_not_required");
        }
        if (!keycloak.getEffectiveRealmRoles(accountId).contains(Role.ADMIN.name())) {
            unmet.add("admin_role_missing");
        }
        if (!keycloak.hasPasswordCredential(accountId)) {
            unmet.add("password_credential_missing");
        }
        boolean linked = personRepository.findByUserId(accountId)
                .map(person -> person.getId().equals(personId))
                .orElse(false);
        if (!linked) {
            unmet.add("person_link_missing");
        }
        return unmet;
    }

    // ── Compensación ───────────────────────────────────────────────────────

    /**
     * Borra la cuenta que creó este intento, solo si sigue desactivada y sin Person
     * vinculada, y confirma que ya no existe. Si algo de eso no se puede comprobar, no se
     * acepta como compensada.
     */
    private AdminBootstrapResult compensate(String attempt, UUID ownedAccountId, String failedStage, RuntimeException cause) {
        AdminBootstrapResult.Builder result = AdminBootstrapResult.builder(attempt, failedStage)
                .account(ownedAccountId)
                .reason(failedStage + "_failed")
                .cause(cause);

        try {
            Optional<UserRepresentation> account = keycloak.findUserById(ownedAccountId);
            if (account.isEmpty()) {
                return result.reason("owned_account_missing").build(FAILED_NEEDS_INSPECTION);
            }
            if (!Boolean.FALSE.equals(account.get().isEnabled())) {
                return result.reason("compensation_unsafe_account_enabled").build(FAILED_NEEDS_INSPECTION);
            }
            if (personRepository.existsByUserId(ownedAccountId)) {
                return result.reason("compensation_unsafe_person_linked").build(FAILED_NEEDS_INSPECTION);
            }
        } catch (RuntimeException ex) {
            return result.reason("compensation_check_failed").build(FAILED_NEEDS_INSPECTION);
        }

        try {
            keycloak.deleteUser(ownedAccountId);
        } catch (RuntimeException ex) {
            // El borrado pudo aplicarse aunque la respuesta falle: decide la relectura
            result.observe("delete_error", ex.getClass().getSimpleName());
        }

        try {
            if (keycloak.findUserById(ownedAccountId).isEmpty()) {
                return result.observe("compensated", "owned_disabled_account_deleted").build(FAILED_COMPENSATED);
            }
        } catch (RuntimeException ex) {
            // sin confirmación
        }
        return result.reason("compensation_failed").build(FAILED_NEEDS_INSPECTION);
    }

    private static boolean requiresUpdatePassword(UserRepresentation account) {
        return account.getRequiredActions() != null
                && account.getRequiredActions().contains(KeycloakUserClient.UPDATE_PASSWORD_ACTION);
    }

    private static UUID idOf(UserRepresentation account) {
        return UUID.fromString(account.getId());
    }

    /** Lo observado antes de cualquier escritura. */
    private record Observed(
            Optional<UserRepresentation> account,
            Optional<Person> person,
            Optional<UserRepresentation> accountWithEmail,
            boolean personWithEmail,
            Optional<Person> personOfAccount,
            Optional<Boolean> linkedAccountExists,
            boolean adminAuthorityExists,
            boolean linked
    ) {
        static Observed linked(UserRepresentation account, Person person) {
            return new Observed(Optional.of(account), Optional.of(person), Optional.empty(), false,
                    Optional.empty(), Optional.empty(), false, true);
        }

        List<String> conflicts() {
            List<String> conflicts = new ArrayList<>();

            if (account.isPresent()) {
                UUID accountId = idOf(account.get());
                if (person.isPresent()) {
                    conflicts.add(person.get().getUserId() == null
                            ? "person_exists_without_account_link"
                            : "person_linked_to_other_account");
                } else if (personOfAccount.isPresent()) {
                    conflicts.add("account_linked_to_other_person");
                } else {
                    conflicts.add("keycloak_account_without_person");
                }
                accountWithEmail
                        .filter(other -> !Objects.equals(idOf(other), accountId))
                        .ifPresent(other -> conflicts.add("email_in_use_by_other_account"));
            } else {
                if (person.isPresent()) {
                    if (person.get().getUserId() == null) {
                        conflicts.add("person_exists_without_account");
                    } else if (linkedAccountExists.orElse(false)) {
                        conflicts.add("person_linked_to_other_account");
                    } else {
                        conflicts.add("person_linked_to_missing_account");
                    }
                }
                if (accountWithEmail.isPresent()) {
                    conflicts.add("email_in_use_by_other_account");
                }
            }

            if (personWithEmail && person.isEmpty()) {
                conflicts.add("email_in_use_by_person");
            }
            if (adminAuthorityExists) {
                conflicts.add("existing_admin_authority");
            }
            return conflicts;
        }
    }
}
