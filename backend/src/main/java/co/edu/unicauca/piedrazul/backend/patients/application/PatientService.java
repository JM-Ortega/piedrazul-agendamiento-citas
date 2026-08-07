package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientPublicResponse;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
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
import org.apache.xmlbeans.impl.xb.xsdschema.Public;
import org.springframework.stereotype.Service;
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

    public PatientService(
            PatientRepository patientRepository,
            PersonExternalService personExternalService,
            UserModuleApi userModuleApi,
            UserAccountProvisioningApi userAccountProvisioningApi,
            VerificationModuleApi verificationModuleApi
    ) {
        this.patientRepository = patientRepository;
        this.personExternalService = personExternalService;
        this.userModuleApi = userModuleApi;
        this.userAccountProvisioningApi = userAccountProvisioningApi;
        this.verificationModuleApi = verificationModuleApi;
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

        Patient patient = buildPatient(person, sex, birthDate, guardianPhone);

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

        Patient patient = buildPatient(person, sex, birthDate, guardianPhone);

        return toData(patientRepository.save(patient), person);
    }

    @Override
    public void deletePatient(UUID personId) {
        if (personId == null) {
            throw new InvalidPatientDataException("personId cannot be null");
        }

        patientRepository.deleteById(personId);
    }

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

        UUID userId = userModuleApi.findUserByUsername(username)
                .map(user -> user.id())
                .orElseGet(() -> {
                    validatePassword(password);

                    return userAccountProvisioningApi.getOrCreateUser(
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
                    ).id();
                });

        if (userModuleApi.findUserByUsername(username).isPresent()) {
            userModuleApi.ensurePatientRole(userId);
        }

        PersonSummary person = personExternalService.createPerson(
                identificationType, identification, firstName, lastName, phone, email, userId
        );

        Patient patient = buildPatient(person, sex, birthDate, guardianPhone);

        return toData(patientRepository.save(patient), person);
    }

    public void requestLinkUserAccountCode(String identification) {
        validateIdentification(identification);

        PatientWithPerson found = getPatientByIdentificationOrThrow(identification);
        ensurePersonHasNoLinkedUser(found.person());

        verificationModuleApi.requestCode(
                identification,
                VerificationPurpose.LINK_PATIENT_ACCOUNT,
                found.person().firstName() + " " + found.person().lastName(),
                found.person().phone(),
                found.person().email(),
                found.person().id()
        );
    }

    public PatientData confirmLinkUserAccount(
            String identification,
            String code,
            String password
    ) {
        validateIdentification(identification);
        validateCode(code);

        PatientWithPerson found = getPatientByIdentificationOrThrow(identification);
        ensurePersonHasNoLinkedUser(found.person());

        verificationModuleApi.verifyCode(
                identification,
                VerificationPurpose.LINK_PATIENT_ACCOUNT,
                code
        );

        validatePassword(password);

        UserSummary user = userAccountProvisioningApi.getOrCreateUser(
                new CreateSystemUserRequest(
                        identification,
                        found.person().identificationType(),
                        found.person().firstName(),
                        found.person().lastName(),
                        found.person().email(),
                        found.person().phone(),
                        password
                ),
                List.of(Role.PATIENT)
        );

        personExternalService.linkUserId(found.person().id(), user.id());

        PersonSummary linkedPerson = personExternalService.findById(found.person().id())
                .orElseThrow(() -> new PatientNotFoundException(identification));

        return toData(found.patient(), linkedPerson);
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



    @Transactional(readOnly = true)
    public PatientPublicResponse findPublicByDocumentNumber(String identification) {
        validateIdentification(identification);

        Optional<PersonSummary> personOpt = personExternalService.findByIdentification(identification);
        Optional<Patient> patientOpt = personOpt.flatMap(person -> patientRepository.findById(person.id()));

        boolean hasSystemUser = userModuleApi.findUserByUsername(identification).isPresent();

        if (patientOpt.isPresent()) {
            return PatientPublicResponse.from(personOpt.get(), hasSystemUser);
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

    private PatientWithPerson getPatientByIdentificationOrThrow(String identification) {
        PersonSummary person = personExternalService.findByIdentification(identification)
                .orElseThrow(() -> new PatientNotFoundException(identification));

        Patient patient = patientRepository.findById(person.id())
                .orElseThrow(() -> new PatientNotFoundException(identification));

        return new PatientWithPerson(patient, person);
    }

    private void ensurePersonHasNoLinkedUser(PersonSummary person) {
        if (person.userId() != null) {
            throw new PatientAlreadyLinkedUserException(person.id());
        }
    }

    private Patient buildPatient(PersonSummary person, PatientSex sex, LocalDate birthDate, String guardianPhone) {
        return new Patient(
                person.id(),
                PatientApiMapper.toDomainSex(sex),
                birthDate,
                guardianPhone
        );
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

    private record PatientWithPerson(Patient patient, PersonSummary person) {
    }
}
