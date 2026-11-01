package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.UpdatePatientCommand;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.PatientRegistrationPolicy;
import co.edu.unicauca.piedrazul.backend.patients.events.PatientUpdatedEvent;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.mappers.PatientApiMapper;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Agrupa las escrituras de una edición de paciente en una sola unidad
 * transaccional: datos de la persona, datos del paciente y evento de auditoría
 * se confirman juntos o ninguno.
 *
 * <p>Esto permite que quien orquesta sincronice la cuenta de acceso fuera de la
 * transacción y compense si esta unidad falla.
 */
@Component
public class PatientUpdateFinalizer {

    private final PatientRepository patientRepository;
    private final PersonExternalService personExternalService;
    private final ApplicationEventPublisher eventPublisher;

    public PatientUpdateFinalizer(
            PatientRepository patientRepository,
            PersonExternalService personExternalService,
            ApplicationEventPublisher eventPublisher
    ) {
        this.patientRepository = patientRepository;
        this.personExternalService = personExternalService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public PatientData apply(
            UUID patientId,
            UpdatePatientCommand command,
            PatientChanges changes,
            String performedBy,
            String performedByRole,
            String correlationId
    ) {
        // Misma política que la prevalidación de quien orquesta, como defensa del
        // único punto de escritura.
        PatientRegistrationPolicy.validate(
                command.identificationType(), command.birthDate(), command.guardianPhone());

        PersonSummary person = personExternalService.updatePerson(
                patientId,
                command.identificationType(),
                command.identification(),
                command.firstName(),
                command.lastName(),
                command.phone(),
                command.email()
        );

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));

        patient.update(
                PatientApiMapper.toDomainSex(command.sex()),
                command.birthDate(),
                command.guardianPhone()
        );
        patientRepository.save(patient);

        eventPublisher.publishEvent(new PatientUpdatedEvent(
                patientId.toString(),
                performedBy,
                performedByRole,
                correlationId,
                changes.beforeJson(),
                changes.afterJson()
        ));

        return PatientApiMapper.toPatientData(patient, person);
    }
}
