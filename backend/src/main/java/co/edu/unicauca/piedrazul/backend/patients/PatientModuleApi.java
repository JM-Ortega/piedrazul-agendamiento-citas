package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.RegisterPatientCommand;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PatientModuleApi {

    Optional<PatientData> findById(UUID id);

    Optional<PatientData> findByDocumentNumber(String documentNumber);

    Optional<PatientData> findByUserId(UUID userId);

    boolean existsById(UUID id);

    List<PatientData> findAll();

    PatientData createPatient(
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
    );

    UUID createPatientNoSchedule(
            IdentificationType identificationType,
            String identification,
            String firstName,
            String lastName,
            String phone,
            String email,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    );

    PatientData createPatientForExistingPerson(
            UUID personId,
            PatientSex sex,
            LocalDate birthDate,
            String guardianPhone
    );

    /**
     * Devuelve el paciente existente correspondiente al documento, o lo registra si
     * falta. Si la persona ya existe, conserva sus datos maestros. No crea cuentas
     * ni modifica roles.
     */
    PatientData resolveOrRegisterPatient(RegisterPatientCommand command);

    void deletePatient(UUID personId);

    //Crear el metodo findByIds para el modulo de citas
    List<PatientData> findByIds(Set<UUID> personIds);
}
