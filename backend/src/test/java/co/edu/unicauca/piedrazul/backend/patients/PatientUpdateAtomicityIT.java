package co.edu.unicauca.piedrazul.backend.patients;

import co.edu.unicauca.piedrazul.backend.patients.api.PatientSex;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.UpdatePatientCommand;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientChanges;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientUpdateFinalizer;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import co.edu.unicauca.piedrazul.backend.patients.domain.Sex;
import co.edu.unicauca.piedrazul.backend.patients.events.PatientUpdatedEvent;
import co.edu.unicauca.piedrazul.backend.patients.exception.InvalidPatientDataException;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import co.edu.unicauca.piedrazul.backend.user.exception.PersonAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Los datos de la persona, los del paciente y el evento de auditoría de una
 * edición forman una sola unidad de trabajo. Requiere transacciones y base de
 * datos reales.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PatientUpdateFinalizer.class, PersonExternalServiceImp.class})
@RecordApplicationEvents
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PatientUpdateAtomicityIT extends PostgresIntegrationSupport {

    private static final LocalDate ADULT = LocalDate.now().minusYears(30);

    // El proveedor de identidad no participa en la propiedad bajo prueba.
    @MockitoBean
    private KeycloakUserService keycloakUserService;

    @Autowired
    private PatientUpdateFinalizer finalizer;

    @Autowired
    private PersonExternalServiceImp personExternalService;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEvents events;

    private TransactionTemplate tx;
    private String document;
    private String otherDocument;
    private PersonSummary person;
    private PatientData current;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        String unique = String.valueOf(System.nanoTime()).substring(0, 9);
        document = "1" + unique;
        otherDocument = "2" + unique;

        person = tx.execute(status -> {
            PersonSummary created = personExternalService.createPerson(
                    IdentificationType.CEDULA, document, "Ana", "Ruiz",
                    "3001234567", "ana@example.com", UUID.randomUUID());
            patientRepository.save(new Patient(created.id(), Sex.FEMENINO, ADULT, null));
            return created;
        });

        // Otra persona que ya usa un documento, para provocar el conflicto.
        tx.executeWithoutResult(status -> personExternalService.createPerson(
                IdentificationType.CEDULA, otherDocument, "Luis", "Pérez",
                "3007654321", "luis@example.com", null));

        current = new PatientData(person.id(), person.userId(), IdentificationType.CEDULA, document,
                "Ana", "Ruiz", "3001234567", "ana@example.com", PatientSex.FEMENINO, ADULT, null);
    }

    private UpdatePatientCommand command(String newDocument, PatientSex sex) {
        return new UpdatePatientCommand(
                IdentificationType.CEDULA, newDocument, "Anabel", "Ruiz Peña", "3119998877",
                "nuevo@example.com", sex, LocalDate.now().minusYears(25), null);
    }

    private PatientData apply(UpdatePatientCommand command) {
        return finalizer.apply(person.id(), command, PatientChanges.between(current, command),
                "actor-1", "[DOCTOR]", "corr-1");
    }

    @Test
    void personDataPatientDataAndAuditEventCommitTogether() {
        String newDocument = "3" + document.substring(1);

        PatientData result = apply(command(newDocument, PatientSex.OTRO));

        PersonSummary reloadedPerson = personExternalService.findById(person.id()).orElseThrow();
        Patient reloadedPatient = patientRepository.findById(person.id()).orElseThrow();
        assertThat(result.identification()).isEqualTo(newDocument);
        assertThat(reloadedPerson.identification()).isEqualTo(newDocument);
        assertThat(reloadedPerson.firstName()).isEqualTo("Anabel");
        assertThat(reloadedPerson.phone()).isEqualTo("3119998877");
        assertThat(reloadedPerson.userId()).isEqualTo(person.userId());
        assertThat(reloadedPatient.getSex()).isEqualTo(Sex.OTRO);
        assertThat(reloadedPatient.getBirthDate()).isEqualTo(LocalDate.now().minusYears(25));

        PatientUpdatedEvent event = events.stream(PatientUpdatedEvent.class).findFirst().orElseThrow();
        assertThat(event.patientId()).isEqualTo(person.id().toString());
        assertThat(event.performedBy()).isEqualTo("actor-1");
        assertThat(event.afterState()).contains("identification").doesNotContain(newDocument);
    }

    @Test
    void failureWritingThePatientRollsBackThePersonChanges() {
        // La persona se actualiza primero; el paciente falla después por sexo nulo.
        assertThatThrownBy(() -> apply(command("3" + document.substring(1), null)))
                .isInstanceOf(InvalidPatientDataException.class);

        PersonSummary reloadedPerson = personExternalService.findById(person.id()).orElseThrow();
        assertThat(reloadedPerson.identification()).isEqualTo(document);
        assertThat(reloadedPerson.firstName()).isEqualTo("Ana");
        assertThat(patientRepository.findById(person.id()).orElseThrow().getBirthDate()).isEqualTo(ADULT);
        assertThat(events.stream(PatientUpdatedEvent.class).count()).isZero();
    }

    @Test
    void documentUsedByAnotherPersonIsRejectedAndNothingChanges() {
        assertThatThrownBy(() -> apply(command(otherDocument, PatientSex.OTRO)))
                .isInstanceOf(PersonAlreadyExistsException.class);

        PersonSummary reloadedPerson = personExternalService.findById(person.id()).orElseThrow();
        assertThat(reloadedPerson.identification()).isEqualTo(document);
        assertThat(patientRepository.findById(person.id()).orElseThrow().getSex()).isEqualTo(Sex.FEMENINO);
        assertThat(events.stream(PatientUpdatedEvent.class).count()).isZero();
    }

    @Test
    void keepingTheSameDocumentIsNotAConflictWithItself() {
        PatientData result = apply(command(document, PatientSex.OTRO));

        assertThat(result.identification()).isEqualTo(document);
        assertThat(result.firstName()).isEqualTo("Anabel");
    }
}
