package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientAccountSummary;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.RegisterPatientCommand;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /**
     * Pacientes que tienen cuenta de usuario, ordenados por nombre. Sin {@code search}
     * lista todos; con {@code search} filtra por nombre completo (sin distinguir
     * mayúsculas ni tildes) o por número de documento, por coincidencia parcial.
     *
     * <p>No incluye si la cuenta está activada o desactivada: ese estado vive en el
     * proveedor de identidad, no en este módulo.
     */
    Page<PatientAccountSummary> searchPatientsWithAccount(String search, Pageable pageable);
}
