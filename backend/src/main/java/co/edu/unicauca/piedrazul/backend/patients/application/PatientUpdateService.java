package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.UpdatePatientCommand;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.PatientRegistrationPolicy;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.mappers.PatientApiMapper;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Edición de los datos de un paciente.
 *
 * <p>La persona y su cuenta de acceso viven en sistemas distintos (base de datos
 * y proveedor de identidad) y no comparten transacción. Por eso el orden es:
 * validar todo, sincronizar la cuenta, escribir en base de datos, y si la
 * escritura falla, restaurar la cuenta. Ningún método abre una transacción
 * propia para que las llamadas al proveedor no la mantengan abierta.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class PatientUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PatientUpdateService.class);

    private final PatientRepository patientRepository;
    private final PersonExternalService personExternalService;
    private final UserModuleApi userModuleApi;
    private final PatientUpdateFinalizer patientUpdateFinalizer;
    private final SecurityContextExtractor securityContextExtractor;

    public PatientUpdateService(
            PatientRepository patientRepository,
            PersonExternalService personExternalService,
            UserModuleApi userModuleApi,
            PatientUpdateFinalizer patientUpdateFinalizer,
            SecurityContextExtractor securityContextExtractor
    ) {
        this.patientRepository = patientRepository;
        this.personExternalService = personExternalService;
        this.userModuleApi = userModuleApi;
        this.patientUpdateFinalizer = patientUpdateFinalizer;
        this.securityContextExtractor = securityContextExtractor;
    }

    /** Edición por personal autorizado: puede cambiar todos los datos, incluido el documento. */
    public PatientData updatePatient(UUID patientId, UpdatePatientCommand command) {
        if (patientId == null) {
            throw new InvalidPatientDataException("Id cannot be null");
        }
        if (command == null) {
            throw new InvalidPatientDataException("Los datos del paciente son obligatorios");
        }

        return update(patientId, command);
    }

    /**
     * Edición del propio paciente. Nunca cambia el documento: se conserva el
     * actual sin importar lo que traiga el comando.
     */
    public PatientData updateOwnPatient(UUID userId, UpdatePatientCommand command) {
        if (userId == null) {
            throw new InvalidPatientDataException("UserId cannot be null");
        }
        if (command == null) {
            throw new InvalidPatientDataException("Los datos del paciente son obligatorios");
        }

        PersonSummary person = personExternalService.findByUserId(userId)
                .orElseThrow(() -> new PatientNotFoundException(userId));

        return update(person.id(), command.withDocument(person.identificationType(), person.identification()));
    }

    private PatientData update(UUID patientId, UpdatePatientCommand command) {
        PersonSummary person = personExternalService.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));

        PatientData current = PatientApiMapper.toPatientData(patient, person);

        // Se valida el estado final completo antes de cualquier efecto externo.
        // Un paciente que quedó inconsistente con el tiempo (por ejemplo, un menor
        // con tarjeta de identidad que ya cumplió 18) debe corregirse en la misma
        // edición: citas exige esa coherencia cada vez que lo consulta.
        validate(command);

        PatientChanges changes = PatientChanges.between(current, command);
        if (changes.isEmpty()) {
            return current;
        }

        if (changes.identificationChanged()) {
            personExternalService.requireIdentificationAvailableFor(patientId, command.identification());
        }

        boolean syncAccount = person.userId() != null && changes.accountDataChanged();
        if (syncAccount) {
            userModuleApi.updateUserIdentity(
                    person.userId(),
                    command.identification(),
                    command.firstName(),
                    command.lastName(),
                    command.email()
            );
        }

        try {
            return patientUpdateFinalizer.apply(
                    patientId,
                    command,
                    changes,
                    securityContextExtractor.currentActorId(),
                    securityContextExtractor.currentActorRoles(),
                    MDC.get("correlationId")
            );
        } catch (RuntimeException ex) {
            if (syncAccount) {
                restoreAccount(person);
            }
            throw ex;
        }
    }

    private void restoreAccount(PersonSummary previous) {
        try {
            userModuleApi.updateUserIdentity(
                    previous.userId(),
                    previous.identification(),
                    previous.firstName(),
                    previous.lastName(),
                    previous.email()
            );
        } catch (RuntimeException restoreFailure) {
            // Queda la cuenta con los datos nuevos y la persona con los anteriores.
            log.error("No se pudo restaurar la cuenta {} tras fallar la edición del paciente {}; "
                            + "requiere revisión manual", previous.userId(), previous.id(), restoreFailure);
        }
    }

    private void validate(UpdatePatientCommand command) {
        if (command.identificationType() == null) {
            throw new InvalidPatientDataException("El tipo de documento es obligatorio");
        }
        requireText(command.identification(), "El número de documento es obligatorio");
        requireText(command.firstName(), "El nombre es obligatorio");
        requireText(command.lastName(), "El apellido es obligatorio");
        requireText(command.phone(), "El teléfono es obligatorio");

        if (command.sex() == null) {
            throw new InvalidPatientDataException("El sexo del paciente es obligatorio");
        }
        if (command.birthDate() == null) {
            throw new InvalidPatientDataException("La fecha de nacimiento es obligatoria");
        }

        PatientRegistrationPolicy.validate(
                command.identificationType(), command.birthDate(), command.guardianPhone());
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new InvalidPatientDataException(message);
        }
    }
}
