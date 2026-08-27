package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.RegisterPatientCommand;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientPublicResponse;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.PatientRegistrationPolicy;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyLinkedUserException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.mappers.PatientApiMapper;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.input.CreateSystemUserRequest;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.UserSummary;
import co.edu.unicauca.piedrazul.backend.verification.VerificationModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import co.edu.unicauca.piedrazul.backend.verification.api.VerifiedCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class PatientService implements PatientModuleApi {

    private final PatientRepository patientRepository;
    private final PersonExternalService personExternalService;
    private final UserModuleApi userModuleApi;
    private final UserAccountProvisioningApi userAccountProvisioningApi;
    private final VerificationModuleApi verificationModuleApi;
    private final PatientLinkFinalizer patientLinkFinalizer;

    public PatientService(
            PatientRepository patientRepository,
            PersonExternalService personExternalService,
            UserModuleApi userModuleApi,
            UserAccountProvisioningApi userAccountProvisioningApi,
            VerificationModuleApi verificationModuleApi,
            PatientLinkFinalizer patientLinkFinalizer
    ) {
        this.patientRepository = patientRepository;
        this.personExternalService = personExternalService;
        this.userModuleApi = userModuleApi;
        this.userAccountProvisioningApi = userAccountProvisioningApi;
        this.verificationModuleApi = verificationModuleApi;
        this.patientLinkFinalizer = patientLinkFinalizer;
    }

    @Override
    public PatientData createPatient(
            IdentificationType identificationType,
            String identification,
            String firstName,
            String lastName,
            String phone,
            String email,
            UUID userId,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    ) {
        PersonSummary person = personExternalService.createPerson(
                identificationType, identification, firstName, lastName, phone, email, userId
        );

        Patient patient = PatientFactory.create(person, sex, birthDate, guardianPhone);

        return toData(patientRepository.save(patient), person);
    }

    @Override
    public PatientData createPatientForExistingPerson(
            UUID personId,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    ) {
        PersonSummary person = personExternalService.findById(personId)
                .orElseThrow(() -> new PatientNotFoundException(personId));

        Patient patient = PatientFactory.create(person, sex, birthDate, guardianPhone);

        return toData(patientRepository.save(patient), person);
    }

    @Override
    public PatientData resolveOrRegisterPatient(RegisterPatientCommand command) {
        validateIdentification(command.documentNumber());

        Optional<PersonSummary> personOpt =
                personExternalService.findByIdentification(command.documentNumber());

        if (personOpt.isPresent()) {
            PersonSummary person = personOpt.get();

            Optional<Patient> existingPatient = patientRepository.findById(person.id());
            if (existingPatient.isPresent()) {
                // Ya es paciente: se reutiliza tal cual, sin sobrescribir sus datos.
                return toData(existingPatient.get(), person);
            }

            // La persona existe (por ejemplo, con cuenta de otro rol): solo falta el
            // registro de paciente. Se conservan sus datos maestros y no se toca su cuenta.
            Patient patient = PatientFactory.create(
                    person, command.sex(), command.birthDate(), command.guardianPhone()
            );
            return toData(patientRepository.save(patient), person);
        }

        PersonSummary person = personExternalService.createPerson(
                command.identificationType(),
                command.documentNumber(),
                command.firstName(),
                command.lastName(),
                command.phone(),
                command.email(),
                null
        );

        Patient patient = PatientFactory.create(
                person, command.sex(), command.birthDate(), command.guardianPhone()
        );

        return toData(patientRepository.save(patient), person);
    }

    @Override
    public void deletePatient(UUID personId) {
        if (personId == null) {
            throw new InvalidPatientDataException("personId cannot be null");
        }

        patientRepository.deleteById(personId);
    }

    /**
     * Registra un paciente nuevo junto con su cuenta. No adopta una {@code Person}
     * ni una cuenta preexistentes: si el documento o el username ya existen, se
     * rechaza.
     */
    public PatientData createPatientWithUser(
            String username,
            String password,
            IdentificationType identificationType,
            String identification,
            String firstName,
            String lastName,
            String phone,
            String email,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    ) {
        validateUsername(username);
        validateIdentification(identification);
        validateUsernameMatchesIdentification(username, identification);
        validatePassword(password);

        // Prevalidación: si los datos del paciente ya son inválidos, se rechazan
        // antes de crear la cuenta, para no producir ese efecto externo en vano.
        validatePatientRegistrationData(identificationType, sex, birthDate, guardianPhone);

        personExternalService.requireIdentificationAvailable(identification);

        UserSummary user = userAccountProvisioningApi.createAccount(
                new CreateSystemUserRequest(
                        identification,
                        identificationType,
                        firstName,
                        lastName,
                        email,
                        phone,
                        password
                ),
                List.of(Role.PATIENT)
        );

        PersonSummary person = personExternalService.createPerson(
                identificationType, identification, firstName, lastName, phone, email, user.id()
        );

        Patient patient = PatientFactory.create(person, sex, birthDate, guardianPhone);

        return toData(patientRepository.save(patient), person);
    }

    /**
     * Solicita el código de verificación necesario para completar la habilitación
     * de acceso asociada al documento.
     *
     * @throws PatientNotFoundException si no existe una persona con ese documento
     * @throws PatientAlreadyLinkedUserException si el acceso ya está habilitado
     * @throws InvalidPatientDataException si el documento está en un estado
     * inconsistente
     */
    // Evita mantener una transacción de base de datos durante las consultas al
    // proveedor de identidad.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void requestLinkUserAccountCode(String identification) {
        validateIdentification(identification);

        PersonSummary person = findPersonOrThrow(identification);
        classifyLinkState(person);

        verificationModuleApi.requestCode(
                identification,
                VerificationPurpose.LINK_PATIENT_ACCOUNT,
                person.firstName() + " " + person.lastName(),
                person.phone(),
                person.email(),
                person.id()
        );
    }

    /**
     * Verifica el código y habilita el acceso del paciente asociado al documento,
     * completando lo que falte según el estado de {@code Patient}, la persona y su
     * cuenta.
     *
     * <p>{@code password} se requiere cuando la persona todavía no tiene cuenta
     * vinculada. {@code sex} y {@code birthDate} se requieren cuando todavía no
     * existe {@code Patient}; {@code guardianPhone} se requiere, además, cuando el
     * paciente es menor de edad.
     *
     * @throws PatientNotFoundException si no existe una persona con ese documento
     * @throws PatientAlreadyLinkedUserException si el acceso ya está habilitado
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PatientData confirmLinkUserAccount(
            String identification,
            String code,
            String password,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    ) {
        validateIdentification(identification);
        validateCode(code);

        PersonSummary person = findPersonOrThrow(identification);
        LinkState state = classifyLinkState(person);

        switch (state) {
            case CREATE_ACCOUNT -> validatePassword(password);
            case REGISTER_PATIENT -> validatePatientRegistrationData(
                    person.identificationType(), sex, birthDate, guardianPhone);
            case GRANT_ACCESS -> {
                // No requiere datos adicionales.
            }
        }

        VerifiedCode verifiedCode = verificationModuleApi.verifyCode(
                identification,
                VerificationPurpose.LINK_PATIENT_ACCOUNT,
                code
        );

        switch (state) {
            case CREATE_ACCOUNT -> {
                // El código se reclama antes de tocar el proveedor de identidad, para
                // que dos confirmaciones simultáneas no escriban credenciales distintas.
                patientLinkFinalizer.consumeOtp(verifiedCode);

                UserSummary user = userAccountProvisioningApi.ensureAccount(
                        new CreateSystemUserRequest(
                                identification,
                                person.identificationType(),
                                person.firstName(),
                                person.lastName(),
                                person.email(),
                                person.phone(),
                                password
                        ),
                        List.of()
                );

                patientLinkFinalizer.linkUserAccount(person.id(), user.id());

                // El rol se asigna después de vincular la cuenta: si falla, queda un
                // estado recuperable sin acceso, en vez de acceso sin vínculo.
                userModuleApi.ensurePatientRole(user.id());
            }
            case REGISTER_PATIENT -> {
                // El rol se asigna después de registrar el paciente, para no habilitar
                // acceso sin su registro.
                patientLinkFinalizer.consumeOtpAndRegisterPatient(
                        verifiedCode, person, sex, birthDate, guardianPhone
                );
                userModuleApi.ensurePatientRole(person.userId());
            }
            case GRANT_ACCESS -> {
                patientLinkFinalizer.consumeOtp(verifiedCode);
                userModuleApi.ensurePatientRole(person.userId());
            }
        }

        return reloadPatientData(person.id(), identification);
    }

    public Optional<PatientData> findByUserId(UUID userId) {
        if (userId == null) {
            throw new InvalidPatientDataException("UserId cannot be null");
        }
        return personExternalService.findByUserId(userId)
                .flatMap(person -> patientRepository.findById(person.id())
                        .map(patient -> toData(patient, person)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientData> findById(UUID id) {
        validateId(id);
        return patientRepository.findById(id)
                .flatMap(patient -> personExternalService.findById(id)
                        .map(person -> toData(patient, person)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientData> findByDocumentNumber(String identification) {
        validateIdentification(identification);
        return personExternalService.findByIdentification(identification)
                .flatMap(person -> patientRepository.findById(person.id())
                        .map(patient -> toData(patient, person)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientData> findAll() {
        List<Patient> patients = patientRepository.findAll();

        Set<UUID> personIds = patients.stream()
                .map(Patient::getPersonId)
                .collect(Collectors.toSet());

        Map<UUID, PersonSummary> persons = personExternalService.findByIds(personIds);

        return patients.stream()
                .map(patient -> toData(patient, persons.get(patient.getPersonId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(UUID id) {
        validateId(id);
        return patientRepository.existsById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientData> findByIds(Set<UUID> patientIds) {

        if (patientIds == null || patientIds.isEmpty()) {
            return List.of();
        }

        List<Patient> patients = patientRepository.findAllById(patientIds);

        Map<UUID, PersonSummary> persons =
                personExternalService.findByIds(patientIds);

        return patients.stream()
                .map(patient -> toData(
                        patient,
                        persons.get(patient.getPersonId())
                ))
                .toList();
    }

    /**
     * Devuelve el estado público del documento: si existe {@code Patient}, si la
     * persona tiene cuenta vinculada, si existe cuenta para el documento en el
     * proveedor de identidad y si esa cuenta posee el rol de paciente.
     *
     * @throws PatientNotFoundException si el documento no tiene persona ni cuenta
     * asociada
     */
    // Evita mantener una transacción de base de datos durante las consultas al
    // proveedor de identidad.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PatientPublicResponse findPublicByDocumentNumber(String identification) {
        validateIdentification(identification);

        Optional<PersonSummary> personOpt = personExternalService.findByIdentification(identification);
        Optional<Patient> patientOpt = personOpt.flatMap(person -> patientRepository.findById(person.id()));

        boolean hasSystemUser = userModuleApi.findUserByUsername(identification).isPresent();

        if (patientOpt.isPresent()) {
            PersonSummary person = personOpt.get();
            return PatientPublicResponse.from(person, hasSystemUser, hasPatientRole(person));
        }

        if (personOpt.isPresent()) {
            PersonSummary person = personOpt.get();
            return PatientPublicResponse.fromPersonWithoutPatient(person, hasSystemUser, hasPatientRole(person));
        }

        if (hasSystemUser) {
            return PatientPublicResponse.fromSystemUserOnly(identification);
        }

        throw new PatientNotFoundException(identification);
    }

    @Transactional(readOnly = true)
    public List<PatientData> searchByDocumentNumberPrefix(String identificationPrefix) {
        if (identificationPrefix == null || identificationPrefix.isBlank()) {
            throw new InvalidPatientDataException("Document number prefix cannot be blank");
        }

        return personExternalService.findByIdentificationPrefix(identificationPrefix).stream()
                .map(person -> patientRepository.findById(person.id()).map(patient -> toData(patient, person)))
                .flatMap(Optional::stream)
                .toList();
    }

    public List<IdentificationType> getAllDocumentTypes() {
        return Arrays.asList(IdentificationType.values());
    }

    /**
     * Fase que corresponde al estado real de la persona. Rechaza los estados que no
     * admiten habilitación: ya habilitado, e inconsistente.
     */
    private LinkState classifyLinkState(PersonSummary person) {
        boolean patientExists = patientRepository.existsById(person.id());
        boolean hasAccount = person.userId() != null;

        if (patientExists && !hasAccount) {
            return LinkState.CREATE_ACCOUNT;
        }

        if (patientExists) {
            if (hasPatientRole(person)) {
                throw new PatientAlreadyLinkedUserException(person.id());
            }
            return LinkState.GRANT_ACCESS;
        }

        if (hasAccount) {
            return LinkState.REGISTER_PATIENT;
        }

        throw new InvalidPatientDataException(
                "Estado inconsistente: la persona no tiene cuenta ni registro de paciente"
        );
    }

    private boolean hasPatientRole(PersonSummary person) {
        return person.userId() != null
                && userModuleApi.getUserRoles(person.userId()).contains(Role.PATIENT.name());
    }

    private PersonSummary findPersonOrThrow(String identification) {
        return personExternalService.findByIdentification(identification)
                .orElseThrow(() -> new PatientNotFoundException(identification));
    }

    private PatientData reloadPatientData(UUID personId, String identification) {
        PersonSummary person = personExternalService.findById(personId)
                .orElseThrow(() -> new PatientNotFoundException(identification));

        Patient patient = patientRepository.findById(personId)
                .orElseThrow(() -> new PatientNotFoundException(identification));

        return toData(patient, person);
    }

    private PatientData toData(Patient patient, PersonSummary person) {
        return PatientApiMapper.toPatientData(patient, person);
    }

    private void validateIdentification(String identification) {
        if (identification == null || identification.isBlank()) {
            throw new InvalidPatientDataException("Document number cannot be blank");
        }
    }

    private void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new InvalidPatientDataException("Username cannot be blank");
        }
    }

    private void validateUsernameMatchesIdentification(String username, String identification) {
        if (!username.equals(identification)) {
            throw new InvalidPatientDataException("Username must match document number");
        }
    }

    /**
     * Datos necesarios para dar de alta un paciente, comprobados antes de cualquier
     * efecto externo. La obligatoriedad se valida aquí; las reglas de coherencia son
     * las de {@link co.edu.unicauca.piedrazul.backend.patients.domain.PatientRegistrationPolicy},
     * que {@code PatientFactory} vuelve a aplicar como defensa del punto único de
     * construcción.
     */
    private void validatePatientRegistrationData(
            IdentificationType identificationType,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    ) {
        if (sex == null) {
            throw new InvalidPatientDataException("El sexo del paciente es obligatorio");
        }

        if (birthDate == null) {
            throw new InvalidPatientDataException("La fecha de nacimiento es obligatoria");
        }

        PatientRegistrationPolicy.validate(identificationType, birthDate, guardianPhone);
    }

    private void validatePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new InvalidPatientDataException("Password cannot be blank");
        }
    }

    private void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new InvalidPatientDataException("Code cannot be blank");
        }
    }

    private void validateId(UUID id) {
        if (id == null) {
            throw new InvalidPatientDataException("Id cannot be null");
        }
    }

    private enum LinkState {
        /** Existe el paciente pero no tiene cuenta: hay que crearla y vincularla. */
        CREATE_ACCOUNT,
        /** Existe la cuenta pero no el registro de paciente: hay que crearlo. */
        REGISTER_PATIENT,
        /** Existen ambos, solo falta habilitar el acceso como paciente. */
        GRANT_ACCESS
    }
}
