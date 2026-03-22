package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.domain.Gender;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyExistsException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientAlreadyLinkedUserException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
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

    public PatientService(PatientRepository patientRepository, UserModuleApi userModuleApi) {
        this.patientRepository = patientRepository;
        this.userModuleApi = userModuleApi;
    }

    @Override
    public PatientData createPatient(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            Gender gender,
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

        Patient savedPatient = patientRepository.save(patient);
        return toData(savedPatient);
    }

    public PatientData createPatientWithUser(
            String username,
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            Gender gender,
            LocalDate birthDate,
            String guardianPhone
    ) {
        validateUsername(username);
        validateDocumentNumber(documentNumber);
        ensurePatientDoesNotExist(documentNumber);

        UUID userId = userModuleApi.createPatientUser(username);

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

        Patient savedPatient = patientRepository.save(patient);
        return toData(savedPatient);
    }

    public PatientData linkUserToExistingPatient(String documentNumber, String username) {
        validateDocumentNumber(documentNumber);
        validateUsername(username);

        Patient patient = getPatientByDocumentNumberOrThrow(documentNumber);
        ensurePatientHasNoLinkedUser(patient);

        UUID userId = userModuleApi.createPatientUser(username);
        patient.linkUser(userId);

        Patient savedPatient = patientRepository.save(patient);
        return toData(savedPatient);
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
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            Gender gender,
            LocalDate birthDate,
            String guardianPhone,
            UUID userId
    ) {
        return new Patient(
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
    }

    private PatientData toData(Patient patient) {
        return new PatientData(
                patient.getId(),
                patient.getUserId(),
                patient.getDocumentType(),
                patient.getDocumentNumber(),
                patient.getFirstName(),
                patient.getLastName(),
                patient.getPhone(),
                patient.getEmail(),
                patient.getGender(),
                patient.getBirthDate(),
                patient.getGuardianPhone()
        );
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

    private void validateId(UUID id) {
        if (id == null) {
            throw new InvalidPatientDataException("Id cannot be null");
        }
    }
}