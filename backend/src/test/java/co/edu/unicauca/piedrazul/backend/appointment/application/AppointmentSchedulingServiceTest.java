package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.PatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentConfigRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.appointment.events.ScheduledAppointmentEvent;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentSchedulingDisableException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.FirstAppointmentMustBeNeuralTerapyException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.OnlyOneAppointmentPerMonthException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.PatientAlreadyScheduledInSpecialtyException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.PatientScheduleTimeConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSchedulingRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.ResolvedPatient;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentSchedulingServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private DoctorConfigConsultPort doctorConfigConsultPort;
    @Mock
    private AppointmentService appointmentService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private IsNewPatientUseCase isNewPatientUseCase;
    @Mock
    private SecurityContextExtractor securityExtractor;
    @Mock
    private AppointmentConfigRepository appointmentConfigRepository;
    @Mock
    private PatientResolutionStrategy patientResolutionStrategy;

    private AppointmentSchedulingService service;

    private final UUID idDoctor = UUID.randomUUID();
    private final UUID idPatient = UUID.randomUUID();
    private final UUID performedBy = UUID.randomUUID();
    private final LocalDate date = LocalDate.now().plusDays(1);
    private final AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));

    @BeforeEach
    void setUp() {
        service = new AppointmentSchedulingService(
                appointmentRepository, doctorConfigConsultPort, appointmentService,
                eventPublisher, isNewPatientUseCase, securityExtractor, appointmentConfigRepository);
    }

    private PatientSchedulingContext manualContext() {
        return PatientSchedulingContext.manual(
                DocumentType.CEDULA, "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "carlos@correo.com", null);
    }

    private PatientInfo patientInfo() {
        return PatientInfo.of(
                DocumentType.CEDULA, "12345678", "Carlos", "Gomez", "3001234567",
                Gender.MASCULINO, LocalDate.of(1990, 6, 15), "carlos@correo.com", null);
    }

    private Appointment buildAppointment(SpecialtyCode specialty, AppointmentState state,
            SchedulingOrigin origin, LocalDate appointmentDate, AppointmentTime time) {
        return Appointment.reconstruct(
                UUID.randomUUID(), idDoctor, idPatient, specialty, state, appointmentDate, time, origin);
    }

    /** Stubs comunes al camino feliz, sin fijar el flujo (manual/autónomo). */
    private void stubHappyPathCollaborators() {
        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(30);
        when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn("Dra. Prueba");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientResolutionStrategy.resolve(any())).thenReturn(new ResolvedPatient(idPatient, patientInfo()));
        when(isNewPatientUseCase.isNewPatient(idPatient)).thenReturn(false);
        when(appointmentRepository.findByPatientIdAndDate(idPatient, date)).thenReturn(List.of());
        when(securityExtractor.currentActorId()).thenReturn("actor-1");
        when(securityExtractor.currentActorRoles()).thenReturn("[ADMIN]");
    }

    private AppointmentSchedulingRequest expectedRequest(SpecialtyCode specialty) {
        return new AppointmentSchedulingRequest(idDoctor, idPatient, specialty, date, startTime);
    }

    @Nested
    class ScheduleManualTests {

        @Test
        void shouldSaveAppointmentAndPublishEventsOnHappyPath() {
            SpecialtyCode specialty = SpecialtyCode.FISIOTERAPIA;
            stubHappyPathCollaborators();
            AppointmentSchedulingRequest request = expectedRequest(specialty);
            Appointment returned = Appointment.scheduleManual(request);
            Appointment saved = buildAppointment(specialty, AppointmentState.AGENDADA, SchedulingOrigin.MANUAL,
                    date, startTime);
            when(appointmentService.scheduleManual(request, 30, List.of())).thenReturn(returned);
            when(appointmentRepository.save(returned)).thenReturn(saved);

            service.scheduleManual(manualContext(), idDoctor, specialty, date, startTime, performedBy,
                    patientResolutionStrategy);

            verify(appointmentRepository).save(returned);

            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
            List<Object> events = eventCaptor.getAllValues();

            AppointmentScheduledEvent notification = events.stream()
                    .filter(AppointmentScheduledEvent.class::isInstance)
                    .map(AppointmentScheduledEvent.class::cast)
                    .findFirst().orElseThrow();
            assertThat(notification.appointmentId()).isEqualTo(saved.getIdAppointment());
            assertThat(notification.patientId()).isEqualTo(idPatient);
            assertThat(notification.patientName()).isEqualTo("Carlos Gomez");
            assertThat(notification.doctorId()).isEqualTo(idDoctor);
            assertThat(notification.doctorName()).isEqualTo("Dra. Prueba");
            assertThat(notification.specialty()).isEqualTo("FISIOTERAPIA");
            assertThat(notification.performedBy()).isEqualTo(performedBy);

            ScheduledAppointmentEvent audit = events.stream()
                    .filter(ScheduledAppointmentEvent.class::isInstance)
                    .map(ScheduledAppointmentEvent.class::cast)
                    .findFirst().orElseThrow();
            assertThat(audit.citaId()).isEqualTo(saved.getIdAppointment());
        }

        @Test
        void shouldThrowWhenNewPatientBooksNonNeuralSpecialty() {
            SpecialtyCode specialty = SpecialtyCode.FISIOTERAPIA;
            when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(30);
            when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn("Dra. Prueba");
            when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
            when(patientResolutionStrategy.resolve(any())).thenReturn(new ResolvedPatient(idPatient, patientInfo()));
            when(isNewPatientUseCase.isNewPatient(idPatient)).thenReturn(true);

            assertThatThrownBy(() -> service.scheduleManual(manualContext(), idDoctor, specialty, date, startTime,
                    performedBy, patientResolutionStrategy))
                    .isInstanceOf(FirstAppointmentMustBeNeuralTerapyException.class);

            verify(appointmentRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void shouldAllowDuplicateSpecialtyAppointmentsInManualFlow() {
            // A diferencia del flujo autónomo, el manual no valida especialidad única:
            // por eso no se stubea findByPatientId — no debe invocarse en absoluto.
            SpecialtyCode specialty = SpecialtyCode.FISIOTERAPIA;
            stubHappyPathCollaborators();
            AppointmentSchedulingRequest request = expectedRequest(specialty);
            Appointment returned = Appointment.scheduleManual(request);
            Appointment saved = buildAppointment(specialty, AppointmentState.AGENDADA, SchedulingOrigin.MANUAL,
                    date, startTime);
            when(appointmentService.scheduleManual(request, 30, List.of())).thenReturn(returned);
            when(appointmentRepository.save(returned)).thenReturn(saved);

            service.scheduleManual(manualContext(), idDoctor, specialty, date, startTime, performedBy,
                    patientResolutionStrategy);

            verify(appointmentRepository, never()).findByPatientId(any());
            verify(appointmentRepository).save(returned);
        }

        @Test
        void shouldThrowWhenPatientHasActiveAppointmentAtSameDateAndTime() {
            SpecialtyCode specialty = SpecialtyCode.FISIOTERAPIA;
            when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(30);
            when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn("Dra. Prueba");
            when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
            when(patientResolutionStrategy.resolve(any())).thenReturn(new ResolvedPatient(idPatient, patientInfo()));
            when(isNewPatientUseCase.isNewPatient(idPatient)).thenReturn(false);
            Appointment conflicting = buildAppointment(SpecialtyCode.QUIROPRAXIA, AppointmentState.AGENDADA,
                    SchedulingOrigin.MANUAL, date, new AppointmentTime(LocalTime.of(9, 0)));
            when(appointmentRepository.findByPatientIdAndDate(idPatient, date)).thenReturn(List.of(conflicting));

            assertThatThrownBy(() -> service.scheduleManual(manualContext(), idDoctor, specialty, date, startTime,
                    performedBy, patientResolutionStrategy))
                    .isInstanceOf(PatientScheduleTimeConflictException.class);

            verify(appointmentRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    class ScheduleAutonomousTests {

        @Test
        void shouldSaveAppointmentAndPublishEventsOnHappyPath() {
            SpecialtyCode specialty = SpecialtyCode.FISIOTERAPIA;
            when(appointmentConfigRepository.isAutonomousSchedulingEnabled()).thenReturn(true);
            stubHappyPathCollaborators();
            when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of());
            AppointmentSchedulingRequest request = expectedRequest(specialty);
            Appointment returned = Appointment.scheduleAutonomous(request);
            Appointment saved = buildAppointment(specialty, AppointmentState.AGENDADA, SchedulingOrigin.AUTONOMO,
                    date, startTime);
            when(appointmentService.scheduleAutonomous(request, 30, List.of())).thenReturn(returned);
            when(appointmentRepository.save(returned)).thenReturn(saved);

            service.scheduleAutonomous(PatientSchedulingContext.autonomous(idPatient), idDoctor, specialty, date,
                    startTime, performedBy, patientResolutionStrategy);

            verify(appointmentRepository).save(returned);
            // any(Object.class) fuerza la resolución a publishEvent(Object); any() sin tipo
            // resuelve en compilación a la sobrecarga publishEvent(ApplicationEvent), que
            // esta clase nunca invoca (sus eventos son records planos, no ApplicationEvent).
            verify(eventPublisher, times(2)).publishEvent(any(Object.class));
        }

        @Test
        void shouldThrowWhenAutonomousSchedulingIsDisabled() {
            when(appointmentConfigRepository.isAutonomousSchedulingEnabled()).thenReturn(false);

            assertThatThrownBy(() -> service.scheduleAutonomous(PatientSchedulingContext.autonomous(idPatient),
                    idDoctor, SpecialtyCode.FISIOTERAPIA, date, startTime, performedBy, patientResolutionStrategy))
                    .isInstanceOf(AppointmentSchedulingDisableException.class);

            verifyNoInteractions(patientResolutionStrategy, doctorConfigConsultPort, appointmentRepository,
                    eventPublisher, isNewPatientUseCase, securityExtractor);
        }

        @Test
        void shouldThrowWhenPatientAlreadyHasAnAutonomousAppointmentThisMonth() {
            SpecialtyCode specialty = SpecialtyCode.FISIOTERAPIA;
            when(appointmentConfigRepository.isAutonomousSchedulingEnabled()).thenReturn(true);
            when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(30);
            when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn("Dra. Prueba");
            when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
            when(patientResolutionStrategy.resolve(any())).thenReturn(new ResolvedPatient(idPatient, patientInfo()));
            when(isNewPatientUseCase.isNewPatient(idPatient)).thenReturn(false);
            when(appointmentRepository.findByPatientIdAndDate(idPatient, date)).thenReturn(List.of());
            // Especialidad distinta a la solicitada, para no disparar antes la excepción de especialidad única
            Appointment thisMonthAutonomous = buildAppointment(SpecialtyCode.QUIROPRAXIA, AppointmentState.AGENDADA,
                    SchedulingOrigin.AUTONOMO, LocalDate.now(), new AppointmentTime(LocalTime.of(8, 0)));
            when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of(thisMonthAutonomous));

            assertThatThrownBy(() -> service.scheduleAutonomous(PatientSchedulingContext.autonomous(idPatient),
                    idDoctor, specialty, date, startTime, performedBy, patientResolutionStrategy))
                    .isInstanceOf(OnlyOneAppointmentPerMonthException.class);

            verify(appointmentRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void shouldNotCountReprogramadaCanceladaOrNoAsistioTowardsMonthlyQuota() {
            SpecialtyCode specialty = SpecialtyCode.FISIOTERAPIA;
            when(appointmentConfigRepository.isAutonomousSchedulingEnabled()).thenReturn(true);
            stubHappyPathCollaborators();
            List<Appointment> nonBlockingAppointments = List.of(
                    buildAppointment(SpecialtyCode.QUIROPRAXIA, AppointmentState.REPROGRAMADA,
                            SchedulingOrigin.AUTONOMO, LocalDate.now(), new AppointmentTime(LocalTime.of(8, 0))),
                    buildAppointment(SpecialtyCode.QUIROPRAXIA, AppointmentState.CANCELADA,
                            SchedulingOrigin.AUTONOMO, LocalDate.now(), new AppointmentTime(LocalTime.of(8, 30))),
                    buildAppointment(SpecialtyCode.QUIROPRAXIA, AppointmentState.NO_ASISTIO,
                            SchedulingOrigin.AUTONOMO, LocalDate.now(), new AppointmentTime(LocalTime.of(9, 0))));
            when(appointmentRepository.findByPatientId(idPatient)).thenReturn(nonBlockingAppointments);
            AppointmentSchedulingRequest request = expectedRequest(specialty);
            Appointment returned = Appointment.scheduleAutonomous(request);
            Appointment saved = buildAppointment(specialty, AppointmentState.AGENDADA, SchedulingOrigin.AUTONOMO,
                    date, startTime);
            when(appointmentService.scheduleAutonomous(request, 30, List.of())).thenReturn(returned);
            when(appointmentRepository.save(returned)).thenReturn(saved);

            service.scheduleAutonomous(PatientSchedulingContext.autonomous(idPatient), idDoctor, specialty, date,
                    startTime, performedBy, patientResolutionStrategy);

            verify(appointmentRepository).save(returned);
        }

        @Test
        void shouldThrowWhenPatientAlreadyHasAScheduledAppointmentInTheSameSpecialty() {
            SpecialtyCode specialty = SpecialtyCode.FISIOTERAPIA;
            when(appointmentConfigRepository.isAutonomousSchedulingEnabled()).thenReturn(true);
            when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(30);
            when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn("Dra. Prueba");
            when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
            when(patientResolutionStrategy.resolve(any())).thenReturn(new ResolvedPatient(idPatient, patientInfo()));
            when(isNewPatientUseCase.isNewPatient(idPatient)).thenReturn(false);
            Appointment sameSpecialtyAgendada = buildAppointment(specialty, AppointmentState.AGENDADA,
                    SchedulingOrigin.AUTONOMO, date.plusDays(10), new AppointmentTime(LocalTime.of(8, 0)));
            when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of(sameSpecialtyAgendada));

            assertThatThrownBy(() -> service.scheduleAutonomous(PatientSchedulingContext.autonomous(idPatient),
                    idDoctor, specialty, date, startTime, performedBy, patientResolutionStrategy))
                    .isInstanceOf(PatientAlreadyScheduledInSpecialtyException.class);

            verify(appointmentRepository, never()).save(any());
            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    class OrchestrationTests {

        @Test
        void scheduleManualShouldCallCollaboratorsInExpectedOrder() {
            SpecialtyCode specialty = SpecialtyCode.FISIOTERAPIA;
            stubHappyPathCollaborators();
            AppointmentSchedulingRequest request = expectedRequest(specialty);
            Appointment returned = Appointment.scheduleManual(request);
            Appointment saved = buildAppointment(specialty, AppointmentState.AGENDADA, SchedulingOrigin.MANUAL,
                    date, startTime);
            when(appointmentService.scheduleManual(request, 30, List.of())).thenReturn(returned);
            when(appointmentRepository.save(returned)).thenReturn(saved);

            service.scheduleManual(manualContext(), idDoctor, specialty, date, startTime, performedBy,
                    patientResolutionStrategy);

            var order = inOrder(doctorConfigConsultPort, appointmentRepository, patientResolutionStrategy,
                    appointmentService);
            order.verify(doctorConfigConsultPort).getIntervalMinutesByDoctor(idDoctor);
            order.verify(doctorConfigConsultPort).getDoctorName(idDoctor);
            order.verify(appointmentRepository).findByDoctorIdAndDate(idDoctor, date);
            order.verify(patientResolutionStrategy).resolve(any());
            order.verify(appointmentRepository).findByPatientIdAndDate(idPatient, date);
            order.verify(appointmentService).scheduleManual(request, 30, List.of());
            order.verify(appointmentRepository).save(returned);
        }
    }
}
