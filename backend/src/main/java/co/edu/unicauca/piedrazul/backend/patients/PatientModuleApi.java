package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientUserRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientModuleApi {

    Optional<PatientData> findById(UUID id);

    Optional<PatientData> findByDocumentNumber(String documentNumber);

    Optional<PatientData> findByUserId(UUID userId);

    boolean existsById(UUID id);

    List<PatientData> findAll();

    PatientData createPatient(
            PatientDocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            PatientGender gender,
            LocalDate birthDate,
            String guardianPhone
    );

    void createPatient(UUID userId, String firstName, String lastName, String identificacion,
                               String email, CreatePatientUserRequest request);
}