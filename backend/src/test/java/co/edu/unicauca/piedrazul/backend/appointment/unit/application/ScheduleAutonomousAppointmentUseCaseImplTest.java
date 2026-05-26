package co.edu.unicauca.piedrazul.backend.appointment.unit.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientAlreadyScheduledInSpecialtyException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientScheduleTimeConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleAutonomousAppointmentUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private PatientConsultPort patientConsultPort;

    @Mock
    private DoctorConfigConsultPort doctorConfigConsultPort;

    @Mock
    private AppointmentService appointmentService;

    private ScheduleAutonomousAppointmentUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new ScheduleAutonomousAppointmentUseCaseImpl(
                appointmentRepository,
                patientConsultPort,
                doctorConfigConsultPort,
                appointmentService
        );

        lenient().when(appointmentRepository.save(any(Appointment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ─────────────────────────────────────────────
    // Flujo feliz — agenda correctamente
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldSaveAppointmentWhenSlotIsAvailable() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);
        PatientInfo patientInfo = buildPatientInfo();
        Appointment expected = buildAppointment(idDoctor, idPatient, startTime, date);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");

        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date))
                .thenReturn(List.of());

        when(appointmentRepository.findByPatientId(idPatient))
                .thenReturn(List.of());

        when(appointmentRepository.findByPatientIdAndDate(idPatient, date))
                .thenReturn(List.of());

        stubPatientName(idPatient, patientInfo);

        when(appointmentService.scheduleAutonomous(
                any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyList()
        )).thenReturn(expected);

        Appointment result = useCase.scheduleAutonomous(
                idPatient,
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        assertThat(result).isEqualTo(expected);

        verify(appointmentRepository).save(expected);
    }

    // ─────────────────────────────────────────────
    // Validación: paciente ya tiene cita AGENDADA
    // en la misma especialidad
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldThrowWhenPatientAlreadyHasScheduledAppointmentInSameSpecialty() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();

        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");

        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date))
                .thenReturn(List.of());

        Appointment citaExistente = buildAppointmentWithState(
                idDoctor,
                idPatient,
                startTime,
                date,
                Specialty.FISIOTERAPIA,
                AppointmentState.AGENDADA
        );

        when(appointmentRepository.findByPatientId(idPatient))
                .thenReturn(List.of(citaExistente));

        assertThatThrownBy(() ->
                useCase.scheduleAutonomous(
                        idPatient,
                        idDoctor,
                        Specialty.FISIOTERAPIA,
                        date,
                        startTime
                )
        )
                .isInstanceOf(PatientAlreadyScheduledInSpecialtyException.class)
                .hasMessageContaining("ya tiene una cita AGENDADA para la especialidad");

        verify(appointmentRepository, never()).save(any(Appointment.class));

        verify(appointmentService, never()).scheduleAutonomous(
                any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyList()
        );
    }

    @Test
    void scheduleAutonomousShouldNotThrowWhenPatientHasCancelledAppointmentInSameSpecialty() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();

        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        PatientInfo patientInfo = buildPatientInfo();

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");

        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date))
                .thenReturn(List.of());

        Appointment cancelada = buildAppointmentWithState(
                idDoctor,
                idPatient,
                startTime,
                date,
                Specialty.FISIOTERAPIA,
                AppointmentState.CANCELADA
        );

        when(appointmentRepository.findByPatientId(idPatient))
                .thenReturn(List.of(cancelada));

        when(appointmentRepository.findByPatientIdAndDate(idPatient, date))
                .thenReturn(List.of());

        stubPatientName(idPatient, patientInfo);

        when(appointmentService.scheduleAutonomous(
                any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyList()
        )).thenReturn(buildAppointment(idDoctor, idPatient, startTime, date));

        useCase.scheduleAutonomous(
                idPatient,
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        verify(appointmentRepository).save(any(Appointment.class));
    }

    @Test
    void scheduleAutonomousShouldNotThrowWhenPatientHasScheduledAppointmentInDifferentSpecialty() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();

        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        PatientInfo patientInfo = buildPatientInfo();

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");

        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date))
                .thenReturn(List.of());

        Appointment otraEspecialidad = buildAppointmentWithState(
                idDoctor,
                idPatient,
                startTime,
                date,
                Specialty.QUIROPRAXIA,
                AppointmentState.AGENDADA
        );

        when(appointmentRepository.findByPatientId(idPatient))
                .thenReturn(List.of(otraEspecialidad));

        when(appointmentRepository.findByPatientIdAndDate(idPatient, date))
                .thenReturn(List.of());

        stubPatientName(idPatient, patientInfo);

        when(appointmentService.scheduleAutonomous(
                any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyList()
        )).thenReturn(buildAppointment(idDoctor, idPatient, startTime, date));

        useCase.scheduleAutonomous(
                idPatient,
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        verify(appointmentRepository).save(any(Appointment.class));
    }

    // ─────────────────────────────────────────────
    // Validación: conflicto de horario
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldThrowWhenPatientHasActiveAppointmentAtSameTime() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();

        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");

        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date))
                .thenReturn(List.of());

        when(appointmentRepository.findByPatientId(idPatient))
                .thenReturn(List.of());

        Appointment conflicto = buildAppointmentWithState(
                idDoctor,
                idPatient,
                startTime,
                date,
                Specialty.QUIROPRAXIA,
                AppointmentState.AGENDADA
        );

        when(appointmentRepository.findByPatientIdAndDate(idPatient, date))
                .thenReturn(List.of(conflicto));

        assertThatThrownBy(() ->
                useCase.scheduleAutonomous(
                        idPatient,
                        idDoctor,
                        Specialty.FISIOTERAPIA,
                        date,
                        startTime
                )
        )
                .isInstanceOf(PatientScheduleTimeConflictException.class)
                .hasMessageContaining("ya tiene una cita activa");

        verify(appointmentRepository, never()).save(any(Appointment.class));

        verify(appointmentService, never()).scheduleAutonomous(
                any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyList()
        );
    }

    @Test
    void scheduleAutonomousShouldNotThrowWhenPatientHasCancelledAppointmentAtSameTime() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();

        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        PatientInfo patientInfo = buildPatientInfo();

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");

        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date))
                .thenReturn(List.of());

        when(appointmentRepository.findByPatientId(idPatient))
                .thenReturn(List.of());

        Appointment cancelada = buildAppointmentWithState(
                idDoctor,
                idPatient,
                startTime,
                date,
                Specialty.QUIROPRAXIA,
                AppointmentState.CANCELADA
        );

        when(appointmentRepository.findByPatientIdAndDate(idPatient, date))
                .thenReturn(List.of(cancelada));

        stubPatientName(idPatient, patientInfo);

        when(appointmentService.scheduleAutonomous(
                any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyList()
        )).thenReturn(buildAppointment(idDoctor, idPatient, startTime, date));

        useCase.scheduleAutonomous(
                idPatient,
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        verify(appointmentRepository).save(any(Appointment.class));
    }

    // ─────────────────────────────────────────────
    // Verificación de orquestación
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldCallCollaboratorsInCorrectOrder() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();

        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        LocalDate date = LocalDate.now().plusDays(1);

        PatientInfo patientInfo = buildPatientInfo();

        Appointment expected = buildAppointment(
                idDoctor,
                idPatient,
                startTime,
                date
        );

        stubDoctorConfig(idDoctor, 30, "Dr. Lopez");

        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date))
                .thenReturn(List.of());

        when(appointmentRepository.findByPatientId(idPatient))
                .thenReturn(List.of());

        when(appointmentRepository.findByPatientIdAndDate(idPatient, date))
                .thenReturn(List.of());

        stubPatientName(idPatient, patientInfo);

        when(appointmentService.scheduleAutonomous(
                any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyList()
        )).thenReturn(expected);

        useCase.scheduleAutonomous(
                idPatient,
                idDoctor,
                Specialty.FISIOTERAPIA,
                date,
                startTime
        );

        verify(doctorConfigConsultPort, times(1))
                .getDoctorName(idDoctor);

        verify(doctorConfigConsultPort, times(1))
                .getIntervalMinutesByDoctor(idDoctor);

        verify(appointmentRepository, times(1))
                .findByDoctorIdAndDate(idDoctor, date);

        verify(appointmentRepository, times(1))
                .findByPatientId(idPatient);

        verify(appointmentRepository, times(1))
                .findByPatientIdAndDate(idPatient, date);

        verify(appointmentService, times(1)).scheduleAutonomous(
                any(), any(), any(), any(), any(),
                any(), any(), any(), anyInt(), anyList()
        );

        verify(appointmentRepository, times(1))
                .save(expected);
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private void stubDoctorConfig(UUID idDoctor, int interval, String name) {
        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor))
                .thenReturn(interval);

        when(doctorConfigConsultPort.getDoctorName(idDoctor))
                .thenReturn(name);
    }

    private void stubPatientName(UUID idPatient, PatientInfo patientInfo) {
        when(patientConsultPort.findById(idPatient))
                .thenReturn(patientInfo);
    }

    private Appointment buildAppointment(
            UUID idDoctor,
            UUID idPatient,
            AppointmentTime startTime,
            LocalDate date
    ) {
        return buildAppointmentWithState(
                idDoctor,
                idPatient,
                startTime,
                date,
                Specialty.FISIOTERAPIA,
                AppointmentState.AGENDADA
        );
    }

    private Appointment buildAppointmentWithState(
            UUID idDoctor,
            UUID idPatient,
            AppointmentTime startTime,
            LocalDate date,
            Specialty specialty,
            AppointmentState state
    ) {
        return Appointment.reconstruct(
                UUID.randomUUID(),
                idDoctor,
                "Dr. Lopez",
                idPatient,
                "Carlos Gomez",
                buildPatientInfo(),
                specialty,
                state,
                date,
                startTime,
                SchedulingOrigin.AUTONOMO
        );
    }

    private PatientInfo buildPatientInfo() {
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