package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientAlreadyScheduledInSpecialtyException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.FirstAppointmentMustBeGeneralMedicineException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientScheduleTimeConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleManualAppointmentUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private DoctorConfigConsultPort doctorConfigConsultPort;

    @Mock
    private AppointmentService appointmentService;

    @Mock
    private PatientConsultPort patientConsultPort;

    private ScheduleManualAppointmentUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new ScheduleManualAppointmentUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                appointmentService,
                patientConsultPort
        );

        lenient().when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        lenient().when(appointmentRepository.existsByPatientIdAndStates(any(UUID.class), anySet()))
                .thenReturn(true);
    }

    // ─────────────────────────────────────────────
    // Flujo feliz — paciente nuevo (no existe en el sistema)
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldCreatePatientAndSaveAppointmentWhenPatientDoesNotExist() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);
        Appointment expectedAppointment = buildAppointment(idDoctor, idPatient, startTime, date);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        // Paciente NO existe → findByDocumentNumber devuelve vacío
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.empty());
        // Se crea el paciente y se obtiene su nuevo id
        when(patientConsultPort.createPatient(any(PatientRegistrationData.class)))
                .thenReturn(idPatient);
        when(appointmentRepository.existsByPatientIdAndStates(eq(idPatient), anySet()))
                .thenReturn(false);
        when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of());
        when(appointmentRepository.findByPatientIdAndDate(idPatient, date)).thenReturn(List.of());
        when(appointmentService.scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        )).thenReturn(expectedAppointment);

        Appointment result = useCase.scheduleManual(
                DocumentType.CEDULA,
                "12345678",          // documentNumber
                "Carlos",            // firstName
                "Gomez",             // lastName
                "3001234567",        // phone
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null,                // guardianPhone
                idDoctor,
                Specialty.MEDICINA_GENERAL,
                date,
                startTime
        );

        assertThat(result).isEqualTo(expectedAppointment);
        // Debe haber creado el paciente en el módulo externo
        verify(patientConsultPort).createPatient(any(PatientRegistrationData.class));
        // Debe haber persistido la cita
        verify(appointmentRepository).save(expectedAppointment);
    }

    @Test
    void scheduleManualShouldSendCorrectDataToCreatePatientWhenPatientDoesNotExist() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientInfo patientInfo = buildAdultoPatientInfo();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.empty());
        when(patientConsultPort.createPatient(any(PatientRegistrationData.class)))
                .thenReturn(idPatient);
        when(appointmentRepository.existsByPatientIdAndStates(eq(idPatient), anySet()))
                .thenReturn(false);
        when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of());
        when(appointmentRepository.findByPatientIdAndDate(idPatient, date)).thenReturn(List.of());
        when(appointmentService.scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        )).thenReturn(buildAppointment(idDoctor, idPatient, startTime, date));

        useCase.scheduleManual(
                DocumentType.CEDULA,
                "12345678",          // documentNumber
                "Carlos",            // firstName
                "Gomez",             // lastName
                "3001234567",        // phone
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null,                // guardianPhone
                idDoctor,
                Specialty.MEDICINA_GENERAL,
                date,
                startTime
        );

        // Capturamos el argumento real que se le pasó a createPatient para verificar
        // que los datos del PatientInfo se mapearon correctamente
        ArgumentCaptor<PatientRegistrationData> captor =
                ArgumentCaptor.forClass(PatientRegistrationData.class);
        verify(patientConsultPort).createPatient(captor.capture());

        PatientRegistrationData sent = captor.getValue();
        assertThat(sent.documentNumber()).isEqualTo(patientInfo.getDocumentNumber());
        assertThat(sent.firstName()).isEqualTo(patientInfo.getFirstName());
        assertThat(sent.lastName()).isEqualTo(patientInfo.getLastName());
        assertThat(sent.phone()).isEqualTo(patientInfo.getPhone());

    }

    @Test
    void scheduleManualShouldThrowWhenNewPatientFirstAppointmentIsNotGeneralMedicine() {
        UUID idDoctor = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientConsultPort.findByDocumentNumber("12345678")).thenReturn(Optional.empty());
        when(patientConsultPort.createPatient(any(PatientRegistrationData.class))).thenReturn(idPatient);
        when(appointmentRepository.existsByPatientIdAndStates(eq(idPatient), anySet())).thenReturn(false);

        assertThatThrownBy(() ->
                useCase.scheduleManual(
                        DocumentType.CEDULA,
                        "12345678",
                        "Carlos",
                        "Gomez",
                        "3001234567",
                        Gender.MASCULINO,
                        LocalDate.of(1990, 6, 15),
                        "carlos@correo.com",
                        null,
                        idDoctor,
                        Specialty.FISIOTERAPIA,
                        date,
                        startTime
                )
        )
                .isInstanceOf(FirstAppointmentMustBeGeneralMedicineException.class)
                .hasMessageContaining("primera cita");

        verify(appointmentRepository, never()).save(any(Appointment.class));
        verify(appointmentService, never()).scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        );
    }

    // ─────────────────────────────────────────────
    // Flujo feliz — paciente existente
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldReuseExistingPatientAndNotCreateNewOne() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientInfo patientInfo = buildAdultoPatientInfo();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        // Paciente SÍ existe → findByDocumentNumber devuelve el snapshot
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.of(new PatientSnapshot(idPatient, patientInfo)));
        when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of());
        when(appointmentRepository.findByPatientIdAndDate(idPatient, date)).thenReturn(List.of());
        when(appointmentService.scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        )).thenReturn(buildAppointment(idDoctor, idPatient, startTime, date));

        useCase.scheduleManual(
                DocumentType.CEDULA,
                "12345678",          // documentNumber
                "Carlos",            // firstName
                "Gomez",             // lastName
                "3001234567",        // phone
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null,                // guardianPhone
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        // Nunca debe crear un nuevo paciente si ya existe
        verify(patientConsultPort, never()).createPatient(any(PatientRegistrationData.class));
        verify(appointmentRepository).save(any(Appointment.class));
    }

    @Test
    void scheduleManualShouldUseExistingPatientIdWhenPatientAlreadyExists() {
        UUID idDoctor      = UUID.randomUUID();
        UUID idPatientReal = UUID.randomUUID(); // el id que ya tiene en el sistema
        PatientInfo patientInfo = buildAdultoPatientInfo();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.of(new PatientSnapshot(idPatientReal, patientInfo)));
        when(appointmentRepository.findByPatientId(idPatientReal)).thenReturn(List.of());
        when(appointmentRepository.findByPatientIdAndDate(idPatientReal, date)).thenReturn(List.of());

        ArgumentCaptor<UUID> idPatientCaptor = ArgumentCaptor.forClass(UUID.class);
        when(appointmentService.scheduleManual(
                any(), idPatientCaptor.capture(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        )).thenReturn(buildAppointment(idDoctor, idPatientReal, startTime, date));

        useCase.scheduleManual(
                DocumentType.CEDULA,
                "12345678",          // documentNumber
                "Carlos",            // firstName
                "Gomez",             // lastName
                "3001234567",        // phone
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null,                // guardianPhone
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        // El id que se le pasa a scheduleManual debe ser el del paciente existente
        assertThat(idPatientCaptor.getValue()).isEqualTo(idPatientReal);
    }

    // ─────────────────────────────────────────────
    // Validación: paciente ya tiene cita AGENDADA en la misma especialidad
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldThrowWhenPatientAlreadyHasScheduledAppointmentInSameSpecialty() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientInfo patientInfo = buildAdultoPatientInfo();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.of(new PatientSnapshot(idPatient, patientInfo)));

        // Ya tiene una cita AGENDADA en FISIOTERAPIA
        Appointment citaExistente = buildAppointmentWithState(
                idDoctor, idPatient, startTime, date,
                Specialty.FISIOTERAPIA, AppointmentState.AGENDADA
        );
        when(appointmentRepository.findByPatientId(idPatient))
                .thenReturn(List.of(citaExistente));

        assertThatThrownBy(() ->
                useCase.scheduleManual(
                        DocumentType.CEDULA,
                        "12345678",          // documentNumber
                        "Carlos",            // firstName
                        "Gomez",             // lastName
                        "3001234567",        // phone
                        Gender.MASCULINO,
                        LocalDate.of(1990, 6, 15),
                        "carlos@correo.com",
                        null,                // guardianPhone
                        idDoctor,
                        Specialty.FISIOTERAPIA,
                        date,
                        startTime
                )
        )
                .isInstanceOf(PatientAlreadyScheduledInSpecialtyException.class)
                .hasMessageContaining("ya tiene una cita AGENDADA para la especialidad");

        // No debe persistir ni crear la cita
        verify(appointmentRepository, never()).save(any(Appointment.class));
        verify(appointmentService, never()).scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        );
    }

    @Test
    void scheduleManualShouldNotThrowWhenPatientHasCanceledAppointmentInSameSpecialty() {
        // CANCELADA no es AGENDADA → debe poder agendar de nuevo
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientInfo patientInfo = buildAdultoPatientInfo();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.of(new PatientSnapshot(idPatient, patientInfo)));

        Appointment cancelada = buildAppointmentWithState(
                idDoctor, idPatient, startTime, date,
                Specialty.FISIOTERAPIA, AppointmentState.CANCELADA
        );
        when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of(cancelada));
        when(appointmentRepository.findByPatientIdAndDate(idPatient, date)).thenReturn(List.of());
        when(appointmentService.scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        )).thenReturn(buildAppointment(idDoctor, idPatient, startTime, date));

        useCase.scheduleManual(
                DocumentType.CEDULA,
                "12345678",          // documentNumber
                "Carlos",            // firstName
                "Gomez",             // lastName
                "3001234567",        // phone
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null,                // guardianPhone
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        verify(appointmentRepository).save(any(Appointment.class));
    }

    @Test
    void scheduleManualShouldNotThrowWhenPatientHasScheduledAppointmentInDifferentSpecialty() {
        // Cita AGENDADA en otra especialidad → no debe bloquear
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientInfo patientInfo = buildAdultoPatientInfo();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.of(new PatientSnapshot(idPatient, patientInfo)));

        Appointment otraEspecialidad = buildAppointmentWithState(
                idDoctor, idPatient, startTime, date,
                Specialty.QUIROPRAXIA, AppointmentState.AGENDADA  // diferente especialidad
        );
        when(appointmentRepository.findByPatientId(idPatient))
                .thenReturn(List.of(otraEspecialidad));
        when(appointmentRepository.findByPatientIdAndDate(idPatient, date)).thenReturn(List.of());
        when(appointmentService.scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        )).thenReturn(buildAppointment(idDoctor, idPatient, startTime, date));

        useCase.scheduleManual(
                DocumentType.CEDULA,
                "12345678",          // documentNumber
                "Carlos",            // firstName
                "Gomez",             // lastName
                "3001234567",        // phone
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null,                // guardianPhone
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        verify(appointmentRepository).save(any(Appointment.class));
    }

    // ─────────────────────────────────────────────
    // Validación: conflicto de horario para el paciente
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldThrowWhenPatientHasActiveAppointmentAtSameTime() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientInfo patientInfo = buildAdultoPatientInfo();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.of(new PatientSnapshot(idPatient, patientInfo)));
        when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of());

        // Ya tiene una cita activa a la misma hora ese día
        Appointment conflicto = buildAppointmentWithState(
                idDoctor, idPatient, startTime, date,
                Specialty.QUIROPRAXIA, AppointmentState.AGENDADA
        );
        when(appointmentRepository.findByPatientIdAndDate(idPatient, date))
                .thenReturn(List.of(conflicto));

        assertThatThrownBy(() ->
                useCase.scheduleManual(
                        DocumentType.CEDULA,
                        "12345678",          // documentNumber
                        "Carlos",            // firstName
                        "Gomez",             // lastName
                        "3001234567",        // phone
                        Gender.MASCULINO,
                        LocalDate.of(1990, 6, 15),
                        "carlos@correo.com",
                        null,                // guardianPhone
                        idDoctor,
                        Specialty.FISIOTERAPIA,
                        date,
                        startTime
                )
        )
                .isInstanceOf(PatientScheduleTimeConflictException.class)
                .hasMessageContaining("ya tiene una cita activa");

        verify(appointmentRepository, never()).save(any(Appointment.class));
        verify(appointmentService, never()).scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        );
    }

    @Test
    void scheduleManualShouldNotThrowWhenPatientHasInactiveAppointmentAtSameTime() {
        // Una cita CANCELADA a la misma hora no debe generar conflicto
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientInfo patientInfo = buildAdultoPatientInfo();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.of(new PatientSnapshot(idPatient, patientInfo)));
        when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of());

        Appointment cancelada = buildAppointmentWithState(
                idDoctor, idPatient, startTime, date,
                Specialty.QUIROPRAXIA, AppointmentState.CANCELADA
        );
        when(appointmentRepository.findByPatientIdAndDate(idPatient, date))
                .thenReturn(List.of(cancelada));
        when(appointmentService.scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        )).thenReturn(buildAppointment(idDoctor, idPatient, startTime, date));

        useCase.scheduleManual(
                DocumentType.CEDULA,
                "12345678",          // documentNumber
                "Carlos",            // firstName
                "Gomez",             // lastName
                "3001234567",        // phone
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null,                // guardianPhone
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        verify(appointmentRepository).save(any(Appointment.class));
    }

    // ─────────────────────────────────────────────
    // Verificación de la orquestación completa
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldCallCollaboratorsInCorrectOrder() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientInfo patientInfo = buildAdultoPatientInfo();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);
        Appointment expectedAppointment = buildAppointment(idDoctor, idPatient, startTime, date);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(patientConsultPort.findByDocumentNumber("12345678"))
                .thenReturn(Optional.of(new PatientSnapshot(idPatient, patientInfo)));
        when(appointmentRepository.findByPatientId(idPatient)).thenReturn(List.of());
        when(appointmentRepository.findByPatientIdAndDate(idPatient, date)).thenReturn(List.of());
        when(appointmentService.scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        )).thenReturn(expectedAppointment);

        useCase.scheduleManual(
                DocumentType.CEDULA,
                "12345678",          // documentNumber
                "Carlos",            // firstName
                "Gomez",             // lastName
                "3001234567",        // phone
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null,                // guardianPhone
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        // Verifica que cada colaborador fue invocado exactamente una vez
        verify(doctorConfigConsultPort, times(1)).getIntervalMinutesByDoctor(idDoctor);
        verify(doctorConfigConsultPort, times(1)).getDoctorName(idDoctor);
        verify(appointmentRepository, times(1)).findByDoctorIdAndDate(idDoctor, date);
        verify(appointmentRepository, times(1)).findByPatientId(idPatient);
        verify(appointmentRepository, times(1)).findByPatientIdAndDate(idPatient, date);
        verify(appointmentService, times(1)).scheduleManual(
                any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyList()
        );
        verify(appointmentRepository, times(1)).save(expectedAppointment);
    }

    // ─────────────────────────────────────────────
    // Fixtures y helpers
    // ─────────────────────────────────────────────

    private void stubDoctorConfig(UUID idDoctor, int interval, String name) {
        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(interval);
        when(doctorConfigConsultPort.getDoctorName(idDoctor)).thenReturn(name);
    }

    private Appointment buildAppointment(UUID idDoctor, UUID idPatient,
                                         AppointmentTime startTime, LocalDate date) {
        return buildAppointmentWithState(
                idDoctor, idPatient, startTime, date,
                Specialty.FISIOTERAPIA, AppointmentState.AGENDADA
        );
    }

    private Appointment buildAppointmentWithState(UUID idDoctor, UUID idPatient,
                                                  AppointmentTime startTime, LocalDate date,
                                                  Specialty specialty, AppointmentState state) {
        return Appointment.reconstruct(
                UUID.randomUUID(),
                idDoctor,
                "Dr. Lopez",
                idPatient,
                "Carlos Gomez",
                buildAdultoPatientInfo(),
                specialty,
                state,
                date,
                startTime,
                SchedulingOrigin.MANUAL
        );
    }

    private PatientInfo buildAdultoPatientInfo() {
        return PatientInfo.of(
                DocumentType.CEDULA,
                "12345678",
                "Carlos",
                "Gomez",
                "3001234567",
                Gender.MASCULINO,
                LocalDate.of(1990, 6, 15),
                "carlos@correo.com",
                null
        );
    }
}
