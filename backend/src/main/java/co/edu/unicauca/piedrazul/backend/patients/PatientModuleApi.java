package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientModuleApi {

    Optional<Patient> findById(UUID id);

    Optional<Patient> findByDocumentNumber(String documentNumber);

    boolean existsById(UUID id);

    List<Patient> findAll();

}