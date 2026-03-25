package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.domain.Gender;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientModuleApi {

    Optional<PatientData> findById(UUID id);

    Optional<PatientData> findByDocumentNumber(String documentNumber);

    boolean existsById(UUID id);

    List<PatientData> findAll();

    PatientData createPatient(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            String email,
            Gender gender,
            LocalDate birthDate,
            String guardianPhone
    );
}