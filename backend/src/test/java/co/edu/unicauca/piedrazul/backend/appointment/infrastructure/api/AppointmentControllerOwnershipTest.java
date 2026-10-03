package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.AutonomousPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PagedResult;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.AppointmentRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.ListAppointmentFiltersRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.CitaDtoMapper;
import co.edu.unicauca.piedrazul.backend.config.security.JwtAuthConverter;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cada rol solo actúa sobre sus propias citas (IDOR). El paciente y el médico se identifican por
 * su token; los ids que lleguen en la petición no pueden sustituir esa identidad.
 */
class AppointmentControllerOwnershipTest {

    private static final UUID PATIENT_USER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OWN_PATIENT_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID OTHER_PATIENT_ID = UUID.fromString("22222222-0000-0000-0000-000000000002");

    private static final UUID DOCTOR_USER_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID OWN_DOCTOR_ID = UUID.fromString("33333333-0000-0000-0000-000000000003");
    private static final UUID OTHER_DOCTOR_ID = UUID.fromString("44444444-0000-0000-0000-000000000004");

    private static final UUID APPOINTMENT_ID = UUID.fromString("55555555-0000-0000-0000-000000000005");

    private ListAppointmentsUseCase listAppointmentsUseCase;
    private CancelAppointmentUseCase cancelAppointmentUseCase;
    private AppointmentSchedulingService appointmentSchedulingService;
    private PatientConsultPort patientConsultPort;
    private DoctorConfigConsultPort doctorConfigConsultPort;

    private AppointmentController controller;

    @BeforeEach
    void setUp() {
        listAppointmentsUseCase = mock(ListAppointmentsUseCase.class);
        cancelAppointmentUseCase = mock(CancelAppointmentUseCase.class);
        appointmentSchedulingService = mock(AppointmentSchedulingService.class);
        patientConsultPort = mock(PatientConsultPort.class);
        doctorConfigConsultPort = mock(DoctorConfigConsultPort.class);

        when(patientConsultPort.findByUserId(PATIENT_USER_ID))
                .thenReturn(Optional.of(new PatientSnapshot(OWN_PATIENT_ID, null)));
        when(doctorConfigConsultPort.findByUserId(DOCTOR_USER_ID)).thenReturn(Optional.of(OWN_DOCTOR_ID));
        when(listAppointmentsUseCase.listBy(any(), any(), any(), any(), any()))
                .thenReturn(new PagedResult<>(List.of(), 0, 5, 0, 0));

        controller = new AppointmentController(
                listAppointmentsUseCase,
                mock(CitaDtoMapper.class),
                mock(IsNewPatientUseCase.class),
                mock(UpdateAppointmentStatusUseCase.class),
                cancelAppointmentUseCase,
                mock(GetAppointmentStatesUseCase.class),
                mock(UpdateAutonomousSchedulingUseCase.class),
                mock(GetAutonomousSchedulingContidionUseCase.class),
                mock(RegisterUnscheduledAttentionUseCase.class),
                mock(GetDoctorDailyAgendaUseCase.class),
                mock(CountScheduledAppointmentsUseCase.class),
                mock(CheckExistenceByDocAndStateUseCase.class),
                appointmentSchedulingService,
                mock(ManualPatientResolutionStrategy.class),
                mock(AutonomousPatientResolutionStrategy.class),
                patientConsultPort,
                doctorConfigConsultPort);
    }

    // ---- listado ----

    @Test
    void aPatientListingAppointmentsOnlySeesTheirOwnEvenIfTheyAskForAnotherPatient() {
        ListAppointmentFiltersRequest filters = new ListAppointmentFiltersRequest();
        filters.setIdPatient(OTHER_PATIENT_ID);
        Jwt jwt = jwt(PATIENT_USER_ID, "PATIENT");

        controller.list(filters, jwt, authentication(jwt));

        verify(listAppointmentsUseCase).listBy(any(), eq(OWN_PATIENT_ID), any(), any(), any());
    }

    @Test
    void aDoctorListingAppointmentsOnlySeesTheirOwnEvenIfTheyAskForAnotherDoctor() {
        ListAppointmentFiltersRequest filters = new ListAppointmentFiltersRequest();
        filters.setIdDoctor(OTHER_DOCTOR_ID);
        Jwt jwt = jwt(DOCTOR_USER_ID, "DOCTOR");

        controller.list(filters, jwt, authentication(jwt));

        verify(listAppointmentsUseCase).listBy(eq(OWN_DOCTOR_ID), any(), any(), any(), any());
    }

    // ---- cancelación ----

    @Test
    void aPatientCancellingIsAlwaysCheckedAgainstTheirOwnPatientId() {
        Jwt jwt = jwt(PATIENT_USER_ID, "PATIENT");

        controller.cancelAppointment(APPOINTMENT_ID, jwt, authentication(jwt));

        verify(cancelAppointmentUseCase).cancel(APPOINTMENT_ID, OWN_PATIENT_ID);
    }

    // ---- agendamiento ----

    @Test
    void aPatientSchedulingAutonomouslyForAnotherPatientBooksForThemselves() {
        AppointmentRequest request = appointment(SchedulingOrigin.AUTONOMO);
        request.setPatientId(OTHER_PATIENT_ID);
        Jwt jwt = jwt(PATIENT_USER_ID, "PATIENT");

        controller.scheduleAppointment(request, jwt, authentication(jwt));

        ArgumentCaptor<PatientSchedulingContext> context = ArgumentCaptor.forClass(PatientSchedulingContext.class);
        verify(appointmentSchedulingService).scheduleAutonomous(context.capture(), any(), any(), any(), any(), any(), any());
        assertThat(context.getValue().idPatient()).isEqualTo(OWN_PATIENT_ID);
    }

    /**
     * El agendamiento manual recibe los datos de cualquier persona y no valida el interruptor de
     * agendamiento autónomo. Es de agendadores y médicos: un paciente no puede usarlo.
     */
    @Test
    void aPatientCannotUseManualSchedulingToBookForSomeoneElse() {
        AppointmentRequest request = manualAppointmentForSomeoneElse();
        Jwt jwt = jwt(PATIENT_USER_ID, "PATIENT");

        try {
            controller.scheduleAppointment(request, jwt, authentication(jwt));
        } catch (RuntimeException rejected) {
            // Rechazar la petición es la respuesta correcta.
        }

        verify(appointmentSchedulingService, never())
                .scheduleManual(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aSchedulerCanStillScheduleManually() {
        AppointmentRequest request = manualAppointmentForSomeoneElse();
        Jwt jwt = jwt(UUID.randomUUID(), "SCHEDULER");

        controller.scheduleAppointment(request, jwt, authentication(jwt));

        verify(appointmentSchedulingService).scheduleManual(any(), any(), any(), any(), any(), any(), any());
    }

    // ---- utilidades ----

    /**
     * Datos completos y válidos: {@link #aSchedulerCanStillScheduleManually()} confirma que se
     * agendan, así el rechazo al paciente no puede venir de datos incompletos.
     */
    private static AppointmentRequest manualAppointmentForSomeoneElse() {
        AppointmentRequest request = appointment(SchedulingOrigin.MANUAL);
        request.setDocumentType(DocumentType.CEDULA);
        request.setDocumentNumber("1002002");
        request.setFirstName("Luis");
        request.setLastName("Mora");
        request.setPhone("3000000000");
        request.setGender(Gender.MASCULINO);
        request.setBirthDate(LocalDate.of(1990, 5, 20));
        request.setEmail("luis@correo.com");
        return request;
    }

    private static AppointmentRequest appointment(SchedulingOrigin origin) {
        AppointmentRequest request = new AppointmentRequest();
        request.setSchedulingOrigin(origin);
        request.setDoctorId(OWN_DOCTOR_ID);
        request.setSpecialty(SpecialtyCode.QUIROPRAXIA);
        request.setDate(LocalDate.now().plusDays(7));
        request.setStartTime(LocalTime.of(9, 0));
        return request;
    }

    private static Jwt jwt(UUID userId, String role) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
    }

    private static Authentication authentication(Jwt jwt) {
        return new JwtAuthConverter().convert(jwt);
    }
}
