package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientPublicResponse;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyLinkedUserException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.mappers.PatientApiMapper;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.VerificationModuleApi;
import co.edu.unicauca.piedrazul.backend.verification.api.VerificationPurpose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PatientService implements PatientModuleApi {

    private final PatientRepository patientRepository;
    private final UserModuleApi userModuleApi;
    private final VerificationModuleApi verificationModuleApi;

    public PatientService(
            PatientRepository patientRepository,
            UserModuleApi userModuleApi,
            VerificationModuleApi verificationModuleApi
    ) {
        this.patientRepository = patientRepository;
        this.userModuleApi = userModuleApi;
        this.verificationModuleApi = verificationModuleApi;
    }

    @Override
    public PatientData createPatient(
            PatientDocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            PatientGender gender,
            LocalDate birthDate,
            String guardianPhone
    ) {
        validateDocumentNumber(documentNumber);
        ensurePatientDoesNotExist(documentNumber);

        Patient patient = buildPatient(
                documentType,
                documentNumber,
                firstName,
                lastName,
                phone,
                email,
                gender,
                birthDate,
                guardianPhone,
                null
        );

        return toData(patientRepository.save(patient));
    }

    public PatientData createPatientWithUser(
            String username,
            String password,
            PatientDocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            PatientGender gender,
            LocalDate birthDate,
            String guardianPhone
    ) {
        validateUsername(username);
        validateDocumentNumber(documentNumber);
        validateUsernameMatchesDocumentNumber(username, documentNumber);
        ensurePatientDoesNotExist(documentNumber);

        UUID userId = userModuleApi.findUserIdByUsername(username)
                .orElseGet(() -> {
                    validatePassword(password);
                    return userModuleApi.getOrCreatePatientUser(
                            username,
                            firstName,
                            lastName,
                            email,
                            password
                    );
                });

        if (userModuleApi.findUserIdByUsername(username).isPresent()) {
            userModuleApi.ensurePatientRole(userId);
        }

        Patient patient = buildPatient(
                documentType,
                documentNumber,
                firstName,
                lastName,
                phone,
                email,
                gender,
                birthDate,
                guardianPhone,
                userId
        );

        return toData(patientRepository.save(patient));
    }

    public void requestLinkUserAccountCode(String documentNumber) {
        validateDocumentNumber(documentNumber);

        Patient patient = getPatientByDocumentNumberOrThrow(documentNumber);
        ensurePatientHasNoLinkedUser(patient);
        validatePhone(patient.getPhone());

        verificationModuleApi.requestCode(
                documentNumber,
                VerificationPurpose.LINK_PATIENT_ACCOUNT,
                patient.getPhone()
        );
    }

    public PatientData confirmLinkUserAccount(
            String documentNumber,
            String code,
            String password
    ) {
        validateDocumentNumber(documentNumber);
        validateCode(code);

        Patient patient = getPatientByDocumentNumberOrThrow(documentNumber);
        ensurePatientHasNoLinkedUser(patient);

        verificationModuleApi.verifyCode(
                documentNumber,
                VerificationPurpose.LINK_PATIENT_ACCOUNT,
                code
        );

        Optional<UUID> existingUserId = userModuleApi.findUserIdByUsername(documentNumber);

        UUID userId = existingUserId.orElseGet(() -> {
            validatePassword(password);
            return userModuleApi.getOrCreatePatientUser(
                    documentNumber,
                    patient.getFirstName(),
                    patient.getLastName(),
                    patient.getEmail(),
                    password
            );
        });

        if (existingUserId.isPresent()) {
            userModuleApi.ensurePatientRole(userId);
        }

        patient.linkUser(userId);

        return toData(patientRepository.save(patient));
    }

    public Optional<PatientData> findByUserId(UUID userId) {
        if (userId == null) {
            throw new InvalidPatientDataException("UserId cannot be null");
        }
        return patientRepository.findByUserId(userId)
                .map(this::toData);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientData> findById(UUID id) {
        validateId(id);
        return patientRepository.findById(id)
                .map(this::toData);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientData> findByDocumentNumber(String documentNumber) {
        validateDocumentNumber(documentNumber);
        return patientRepository.findByDocumentNumber(documentNumber)
                .map(this::toData);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientData> findAll() {
        return patientRepository.findAll()
                .stream()
                .map(this::toData)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(UUID id) {
        validateId(id);
        return patientRepository.existsById(id);
    }

    @Transactional(readOnly = true)
    public PatientPublicResponse findPublicByDocumentNumber(String documentNumber) {
        validateDocumentNumber(documentNumber);

        Optional<Patient> patientOpt =
                patientRepository.findByDocumentNumber(documentNumber);

        boolean hasSystemUser =
                userModuleApi.findUserIdByUsername(documentNumber).isPresent();

        if (patientOpt.isPresent()) {
            return PatientPublicResponse.from(patientOpt.get(), hasSystemUser);
        }

        if (hasSystemUser) {
            return PatientPublicResponse.fromSystemUserOnly(documentNumber);
        }

        throw new PatientNotFoundException(documentNumber);
    }

    private void ensurePatientDoesNotExist(String documentNumber) {
        if (patientRepository.existsByDocumentNumber(documentNumber)) {
            throw new PatientAlreadyExistsException(documentNumber);
        }
    }

    private Patient getPatientByDocumentNumberOrThrow(String documentNumber) {
        return patientRepository.findByDocumentNumber(documentNumber)
                .orElseThrow(() -> new PatientNotFoundException(documentNumber));
    }

    private void ensurePatientHasNoLinkedUser(Patient patient) {
        if (patient.hasUserAccount()) {
            throw new PatientAlreadyLinkedUserException(patient.getId());
        }
    }

    private Patient buildPatient(
            PatientDocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            PatientGender gender,
            LocalDate birthDate,
            String guardianPhone,
            UUID userId
    ) {
        return new Patient(
                PatientApiMapper.toDomainDocumentType(documentType),
                documentNumber,
                firstName,
                lastName,
                phone,
                email,
                PatientApiMapper.toDomainGender(gender),
                birthDate,
                guardianPhone,
                userId
        );
    }

    private PatientData toData(Patient patient) {
        return PatientApiMapper.toPatientData(patient);
    }

    private void validateDocumentNumber(String documentNumber) {
        if (documentNumber == null || documentNumber.isBlank()) {
            throw new InvalidPatientDataException("Document number cannot be blank");
        }
    }

    private void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new InvalidPatientDataException("Username cannot be blank");
        }
    }

    private void validateUsernameMatchesDocumentNumber(String username, String documentNumber) {
        if (!username.equals(documentNumber)) {
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

    private void validatePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new InvalidPatientDataException("Patient phone cannot be blank");
        }
    }

    private void validateId(UUID id) {
        if (id == null) {
            throw new InvalidPatientDataException("Id cannot be null");
        }
    }
}