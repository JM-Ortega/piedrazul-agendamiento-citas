package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.application.CancelAppointmentUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.application.RescheduleAppointmentUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.AutonomousPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CancelAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.RescheduleAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentChangeEvents;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentCancelledByStaffEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentRescheduledEvent;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentStateConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentAccessDeniedException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.CancelAppointmentNotAllowedException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.SlotNotAvailableException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
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
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationAttemptProcessor;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationService;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Reagendamiento contra Postgres real: cancelar + agendar es una sola transacción. Si agendar
 * falla, la original no queda cancelada y no sale ningún evento después del commit (los
 * listeners reales de auditoría y notificaciones son {@code @ApplicationModuleListener}, es decir
 * AFTER_COMMIT; aquí un listener AFTER_COMMIT de test hace de testigo).
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
        RescheduleAppointmentIT.RescheduleBeans.class,
        PatientService.class,
        PatientLinkFinalizer.class,
        PersonExternalServiceImp.class,
        VerificationService.class,
        VerificationAttemptProcessor.class,
        JpaVerificationCodeStore.class
})
// Sin transacción del test: la abre el caso de uso, como en producción.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RescheduleAppointmentIT extends PostgresIntegrationSupport {

    private static final LocalDate BIRTH_DATE = LocalDate.now().minusYears(30);
    private static final LocalTime ORIGINAL_TIME = LocalTime.of(9, 0);

    @TestConfiguration
    static class RescheduleBeans {

        @Bean
        AppointmentChangeEvents appointmentChangeEvents(
                org.springframework.context.ApplicationEventPublisher eventPublisher,
                PatientConsultPort patientConsultPort,
                DoctorConfigConsultPort doctorConfigConsultPort) {
            return new AppointmentChangeEvents(eventPublisher, patientConsultPort, doctorConfigConsultPort);
        }

        @Bean
        CancelAppointmentUseCase cancelAppointmentUseCase(
                AppointmentRepository appointmentRepository, AppointmentChangeEvents changeEvents) {
            return new CancelAppointmentUseCaseImpl(appointmentRepository, changeEvents);
        }

        @Bean
        RescheduleAppointmentUseCase rescheduleAppointmentUseCase(
                AppointmentRepository appointmentRepository,
                CancelAppointmentUseCase cancelAppointmentUseCase,
                AppointmentSchedulingService appointmentSchedulingService,
                AutonomousPatientResolutionStrategy autonomousPatientResolutionStrategy,
                AppointmentChangeEvents changeEvents) {
            return new RescheduleAppointmentUseCaseImpl(appointmentRepository, cancelAppointmentUseCase,
                    appointmentSchedulingService, autonomousPatientResolutionStrategy, changeEvents);
        }

        @Bean
        AfterCommitEvents afterCommitEvents() {
            return new AfterCommitEvents();
        }
    }

    /** Registra solo los eventos de cita que llegan tras un commit real. */
    static class AfterCommitEvents {
        final List<Object> received = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(ScheduledAppointmentEvent event) {
            received.add(event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(AppointmentScheduledEvent event) {
            received.add(event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(AppointmentCancelledByStaffEvent event) {
            received.add(event);
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(AppointmentRescheduledEvent event) {
            received.add(event);
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
    private RescheduleAppointmentUseCase rescheduleUseCase;
    @Autowired
    private CancelAppointmentUseCase cancelUseCase;
    @Autowired
    private AppointmentSchedulingService schedulingService;
    @Autowired
    private ManualPatientResolutionStrategy manualStrategy;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private AppointmentJpaRepository appointmentJpaRepository;
    @Autowired
    private DoctorRepository doctorRepository;
    @Autowired
    private PersonExternalService personExternalService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AfterCommitEvents afterCommitEvents;

    private final AtomicLong documentSequence = new AtomicLong(System.nanoTime());

    private UUID doctorId;
    private UUID otherDoctorId;
    private LocalDate date;
    private Appointment original;

    private String uniqueDocument() {
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    private UUID newDoctor() {
        UUID id = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Doc", "Tor", "3000000001", null, null).id();
        doctorRepository.save(new Doctor(id, LocalDate.now(), LocalDate.now().plusYears(1), 4, true, 30));
        return id;
    }

    private PatientSchedulingContext newPatientContext() {
        return PatientSchedulingContext.manual(
                DocumentType.CEDULA, uniqueDocument(), "Ana", "Ruiz", "3001234567",
                Gender.FEMENINO, BIRTH_DATE, "ana@example.com", null);
    }

    private Appointment scheduleManual(UUID doctor, LocalTime time) {
        return schedulingService.scheduleManual(newPatientContext(), doctor, SpecialtyCode.FISIOTERAPIA, date,
                new AppointmentTime(time), UUID.randomUUID(), manualStrategy);
    }

    private Appointment reschedule(UUID requesterDoctorId, UUID newDoctor, LocalTime time) {
        return rescheduleUseCase.reschedule(original.getIdAppointment(), requesterDoctorId, newDoctor,
                SpecialtyCode.QUIROPRAXIA, date, new AppointmentTime(time), UUID.randomUUID());
    }

    private AppointmentEntity reload(UUID appointmentId) {
        return appointmentJpaRepository.findById(appointmentId).orElseThrow();
    }

    private List<AppointmentEntity> appointmentsOfOriginalPatient() {
        return appointmentJpaRepository.findByIdPatient(original.getIdPatient());
    }

    @BeforeEach
    void setUp() {
        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(any())).thenReturn(30);
        when(doctorConfigConsultPort.getDoctorName(any())).thenReturn("Dra. Prueba");

        doctorId = newDoctor();
        otherDoctorId = newDoctor();
        date = LocalDate.now().plusDays(2);
        // Paciente nuevo con especialidad distinta de terapia neural: el flujo manual ya lo permite
        original = scheduleManual(doctorId, ORIGINAL_TIME);
        afterCommitEvents.received.clear();
    }

    @Test
    void shouldCancelTheOriginalAndScheduleTheNewOneForTheSamePatient() {
        Appointment created = reschedule(null, otherDoctorId, LocalTime.of(10, 0));

        assertThat(reload(original.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        AppointmentEntity saved = reload(created.getIdAppointment());
        assertThat(saved.getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        assertThat(saved.getIdPatient()).isEqualTo(original.getIdPatient());
        assertThat(saved.getIdDoctor()).isEqualTo(otherDoctorId);
        assertThat(saved.getSpecialty()).isEqualTo(SpecialtyCode.QUIROPRAXIA);
        assertThat(saved.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.MANUAL);

        // Tras el commit salen los eventos de la cita nueva (auditoría y notificación) y el aviso de
        // reagendamiento, una vez cada uno, y ninguno de cancelación
        assertThat(afterCommitEvents.received).hasSize(3);
        assertThat(afterCommitEvents.received).noneMatch(AppointmentCancelledByStaffEvent.class::isInstance);
        assertThat(afterCommitEvents.received).filteredOn(ScheduledAppointmentEvent.class::isInstance)
                .singleElement()
                .extracting(e -> ((ScheduledAppointmentEvent) e).citaId())
                .isEqualTo(created.getIdAppointment());
        assertThat(afterCommitEvents.received).filteredOn(AppointmentScheduledEvent.class::isInstance)
                .singleElement()
                .extracting(e -> ((AppointmentScheduledEvent) e).appointmentId())
                .isEqualTo(created.getIdAppointment());
    }

    @Test
    void shouldPublishOneRescheduledEventWithThePreviousAndNewDetails() {
        when(doctorConfigConsultPort.getDoctorName(doctorId)).thenReturn("Dr. Anterior");
        when(doctorConfigConsultPort.getDoctorName(otherDoctorId)).thenReturn("Dra. Nueva");

        Appointment created = reschedule(null, otherDoctorId, LocalTime.of(10, 0));

        assertThat(afterCommitEvents.received).filteredOn(AppointmentRescheduledEvent.class::isInstance)
                .singleElement()
                .isEqualTo(new AppointmentRescheduledEvent(
                        original.getIdAppointment(), created.getIdAppointment(), original.getIdPatient(),
                        "Ana Ruiz", "3001234567", "ana@example.com",
                        "Dr. Anterior", date, ORIGINAL_TIME, "FISIOTERAPIA",
                        "Dra. Nueva", date, LocalTime.of(10, 0), "QUIROPRAXIA"));
    }

    @Test
    void aMissingDoctorNameOmitsTheRescheduledEventButNotTheReschedule() {
        when(doctorConfigConsultPort.getDoctorName(otherDoctorId)).thenReturn(null);

        Appointment created = reschedule(null, otherDoctorId, LocalTime.of(10, 0));

        assertThat(reload(created.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        assertThat(reload(original.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        assertThat(afterCommitEvents.received).noneMatch(AppointmentRescheduledEvent.class::isInstance);
    }

    @Test
    void staffCancellationPublishesOneEventAfterCommit() {
        cancelUseCase.cancel(original.getIdAppointment(), null);

        assertThat(afterCommitEvents.received).singleElement()
                .isEqualTo(new AppointmentCancelledByStaffEvent(
                        original.getIdAppointment(), original.getIdPatient(), "Ana Ruiz", "3001234567",
                        "ana@example.com", "Dra. Prueba", date, ORIGINAL_TIME, "FISIOTERAPIA"));
    }

    @Test
    void patientSelfCancellationPublishesNothing() {
        cancelUseCase.cancel(original.getIdAppointment(), original.getIdPatient());

        assertThat(reload(original.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        assertThat(afterCommitEvents.received).isEmpty();
    }

    @Test
    void aFailedOrRepeatedStaffCancellationPublishesNothingMore() {
        cancelUseCase.cancel(original.getIdAppointment(), null);
        afterCommitEvents.received.clear();

        assertThatThrownBy(() -> cancelUseCase.cancel(original.getIdAppointment(), null))
                .isInstanceOf(AppointmentStateConflictException.class);

        assertThat(afterCommitEvents.received).isEmpty();
    }

    @Test
    void aRolledBackStaffCancellationDeliversNoEvent() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            cancelUseCase.cancel(original.getIdAppointment(), null);
            status.setRollbackOnly();
        });

        assertThat(reload(original.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        assertThat(afterCommitEvents.received).isEmpty();
    }

    /**
     * Mismo médico, fecha y hora que la original: solo funciona si la consulta del agendamiento,
     * dentro de la misma transacción, ya ve la original como CANCELADA (slot y horario libres).
     */
    @Test
    void shouldRescheduleIntoTheSameSlotOfTheOriginal() {
        Appointment created = reschedule(null, doctorId, ORIGINAL_TIME);

        assertThat(reload(original.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        assertThat(reload(created.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
    }

    /** Cancelar y consultar en la misma transacción: la consulta posterior ya ve CANCELADA. */
    @Test
    void aQueryAfterCancellingInTheSameTransactionSeesTheAppointmentCancelled() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        AppointmentState seenInsideTransaction = tx.execute(status -> {
            cancelUseCase.cancel(original.getIdAppointment(), null);
            AppointmentState state = appointmentRepository.findByDoctorIdAndDate(doctorId, date).stream()
                    .filter(a -> a.getIdAppointment().equals(original.getIdAppointment()))
                    .findFirst().orElseThrow()
                    .getAppointmentState();
            status.setRollbackOnly();
            return state;
        });

        assertThat(seenInsideTransaction).isEqualTo(AppointmentState.CANCELADA);
        assertThat(reload(original.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
    }

    @Test
    void shouldRollBackTheCancellationWhenSchedulingFails() {
        // Ocupa el slot destino con la cita de otro paciente
        Appointment occupying = scheduleManual(otherDoctorId, LocalTime.of(10, 0));
        afterCommitEvents.received.clear();

        assertThatThrownBy(() -> reschedule(null, otherDoctorId, LocalTime.of(10, 0)))
                .isInstanceOf(SlotNotAvailableException.class);

        assertThat(reload(original.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        assertThat(appointmentsOfOriginalPatient()).singleElement()
                .extracting(AppointmentEntity::getIdAppointment).isEqualTo(original.getIdAppointment());
        assertThat(reload(occupying.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        // Sin commit no hay auditoría ni notificaciones
        assertThat(afterCommitEvents.received).isEmpty();
    }

    @Test
    void aRepeatedRescheduleFailsBecauseTheOriginalIsAlreadyCancelled() {
        reschedule(null, otherDoctorId, LocalTime.of(10, 0));
        afterCommitEvents.received.clear();

        assertThatThrownBy(() -> reschedule(null, otherDoctorId, LocalTime.of(11, 0)))
                .isInstanceOf(CancelAppointmentNotAllowedException.class);

        assertThat(appointmentsOfOriginalPatient()).hasSize(2);
        assertThat(afterCommitEvents.received).isEmpty();
    }

    @Test
    void aDoctorCannotRescheduleAnotherDoctorsAppointment() {
        assertThatThrownBy(() -> reschedule(otherDoctorId, otherDoctorId, LocalTime.of(10, 0)))
                .isInstanceOf(AppointmentAccessDeniedException.class);

        assertThat(reload(original.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        assertThat(appointmentsOfOriginalPatient()).hasSize(1);
    }

    @Test
    void aDoctorCanRescheduleTheirOwnAppointmentToAnotherDoctor() {
        Appointment created = reschedule(doctorId, otherDoctorId, LocalTime.of(10, 0));

        assertThat(reload(original.getIdAppointment()).getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        assertThat(reload(created.getIdAppointment()).getIdDoctor()).isEqualTo(otherDoctorId);
    }
}
