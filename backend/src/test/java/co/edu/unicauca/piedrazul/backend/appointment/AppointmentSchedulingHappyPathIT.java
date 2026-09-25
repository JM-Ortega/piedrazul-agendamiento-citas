package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.AutonomousPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentConfigRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentSchedulingDisableException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration.PatientConsultPortImpl;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration.PatientProvisioningPortImpl;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientLinkFinalizer;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationAttemptProcessor;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationService;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Camino feliz de agendamiento contra Postgres real: verifica que un agendamiento exitoso
 * persiste la cita y publica los dos eventos reales (auditoría + notificación), algo que
 * {@link ManualSchedulingRollbackIT} no cubre porque su escenario nunca llega a guardar nada.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        AppointmentMapper.class,
        PatientConsultPortImpl.class,
        PatientProvisioningPortImpl.class,
        SecurityContextExtractor.class,
        ManualSchedulingRollbackIT.SchedulingBeans.class,
        AppointmentSchedulingHappyPathIT.AutonomousBeans.class,
        PatientService.class,
        PatientLinkFinalizer.class,
        PersonExternalServiceImp.class,
        VerificationService.class,
        VerificationAttemptProcessor.class,
        JpaVerificationCodeStore.class
})
@RecordApplicationEvents
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppointmentSchedulingHappyPathIT extends PostgresIntegrationSupport {

    private static final LocalDate BIRTH_DATE = LocalDate.now().minusYears(30);

    @TestConfiguration
    static class AutonomousBeans {
        @Bean
        AutonomousPatientResolutionStrategy autonomousPatientResolutionStrategy(PatientConsultPort patientConsultPort) {
            return new AutonomousPatientResolutionStrategy(patientConsultPort);
        }
    }

    @MockitoBean
    private DoctorConfigConsultPort doctorConfigConsultPort;
    @MockitoBean
    private KeycloakUserService keycloakUserService;
    @MockitoBean
    private UserAccountProvisioningApi userAccountProvisioningApi;
    @MockitoBean
    private VerificationCodeSender verificationCodeSender;

    @Autowired
    private AppointmentSchedulingService schedulingService;
    @Autowired
    private ManualPatientResolutionStrategy manualStrategy;
    @Autowired
    private AutonomousPatientResolutionStrategy autonomousStrategy;
    @Autowired
    private AppointmentConfigRepository appointmentConfigRepository;
    @Autowired
    private AppointmentJpaRepository appointmentJpaRepository;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private PersonExternalService personExternalService;
    @Autowired
    private ApplicationEvents events;

    private final java.util.concurrent.atomic.AtomicLong documentSequence =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private String uniqueDocument() {
        // Los primeros dígitos de nanoTime() casi no cambian entre llamadas sucesivas
        // (substring(0,10) tomaría siempre el mismo valor); un contador sí garantiza unicidad.
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    private String document;
    private UUID doctorId;

    @BeforeEach
    void setUp() {
        document = uniqueDocument();
        PersonSummary doctorPerson = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Dra", "Prueba", "3000000001", null, null);
        doctorId = doctorPerson.id();
        doctorRepository.save(new Doctor(doctorId, LocalDate.now(), LocalDate.now().plusYears(1), 4, true, 30));

        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(any())).thenReturn(30);
        when(doctorConfigConsultPort.getDoctorName(any())).thenReturn("Dra. Prueba");
    }

    @AfterEach
    void tearDown() {
        // La fila de configuración es un singleton compartido por todo el contenedor de Postgres
        // (reutilizado entre clases de test); si un test la deshabilita, hay que restaurarla.
        appointmentConfigRepository.setAutonomousSchedulingEnabled(true);
    }

    private PatientSchedulingContext manualContext() {
        return PatientSchedulingContext.manual(
                DocumentType.CEDULA, document, "Ana", "Ruiz", "3001234567",
                Gender.FEMENINO, BIRTH_DATE, "ana@example.com", null);
    }

    @Test
    void shouldPersistAppointmentAndPublishBothEventsOnSuccessfulManualScheduling() {
        UUID performedBy = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(1);
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));

        // Paciente nuevo => debe ser TERAPIA_NEURAL para no disparar
        // FirstAppointmentMustBeNeuralTerapyException.
        schedulingService.scheduleManual(manualContext(), doctorId, SpecialtyCode.TERAPIA_NEURAL, date, startTime,
                performedBy, manualStrategy);

        List<AppointmentEntity> saved = appointmentJpaRepository.findByIdDoctorAndDate(doctorId, date);
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        assertThat(saved.getFirst().getSpecialty()).isEqualTo(SpecialtyCode.TERAPIA_NEURAL);

        List<AppointmentScheduledEvent> notificationEvents = events.stream(AppointmentScheduledEvent.class).toList();
        assertThat(notificationEvents).hasSize(1);
        AppointmentScheduledEvent notification = notificationEvents.getFirst();
        assertThat(notification.doctorId()).isEqualTo(doctorId);
        assertThat(notification.doctorName()).isEqualTo("Dra. Prueba");
        assertThat(notification.patientName()).isEqualTo("Ana Ruiz");
        assertThat(notification.performedBy()).isEqualTo(performedBy);
        assertThat(notification.specialty()).isEqualTo("TERAPIA_NEURAL");

        List<ScheduledAppointmentEvent> auditEvents = events.stream(ScheduledAppointmentEvent.class).toList();
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.getFirst().citaId()).isEqualTo(notification.appointmentId());
    }

    @Test
    void shouldThrowWhenAutonomousSchedulingIsDisabled() {
        appointmentConfigRepository.setAutonomousSchedulingEnabled(false);

        assertThatThrownBy(() -> schedulingService.scheduleAutonomous(
                PatientSchedulingContext.autonomous(UUID.randomUUID()), doctorId, SpecialtyCode.TERAPIA_NEURAL,
                LocalDate.now().plusDays(1), new AppointmentTime(LocalTime.of(9, 0)), UUID.randomUUID(),
                autonomousStrategy))
                .isInstanceOf(AppointmentSchedulingDisableException.class);

        assertThat(appointmentJpaRepository.findByIdDoctorAndDate(doctorId, LocalDate.now().plusDays(1))).isEmpty();
    }
}
