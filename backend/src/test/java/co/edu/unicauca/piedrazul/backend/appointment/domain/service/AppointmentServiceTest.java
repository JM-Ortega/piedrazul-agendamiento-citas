package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.SlotNotAvailableException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock
    private BusySlotService busySlotService;

    @Mock
    private SlotTimeService slotTimeService;

    private AppointmentService appointmentService;

    @BeforeEach
    void setUp() {
        appointmentService = new AppointmentService(busySlotService, slotTimeService);
    }

    // ─────────────────────────────────────────────
    // scheduleManual — slot libre
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldReturnAppointmentWhenSlotIsFree() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(false);

        Appointment result = appointmentService.scheduleManual(
                "Dr. Lopez", idPatient, buildPatientInfo(),
                idDoctor, "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, List.of()
        );

        assertThat(result).isNotNull();
        assertThat(result.getIdDoctor()).isEqualTo(idDoctor);
        assertThat(result.getIdPatient()).isEqualTo(idPatient);
        assertThat(result.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.MANUAL);
        assertThat(result.getSpecialty()).isEqualTo(Specialty.FISIOTERAPIA);
        assertThat(result.getStartTime()).isEqualTo(startTime);
    }

    @Test
    void scheduleManualShouldAllowNullIdPatient() {
        // En manual el paciente puede no tener cuenta en el sistema
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(false);

        Appointment result = appointmentService.scheduleManual(
                "Dr. Lopez", null, buildPatientInfo(),
                UUID.randomUUID(), "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, List.of()
        );

        assertThat(result.getIdPatient()).isNull();
    }

    @Test
    void scheduleManualShouldDelegateBusyCheckToBusySlotService() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        List<Appointment> existingAppointments = List.of();

        when(busySlotService.isBusy(existingAppointments, startTime, 30)).thenReturn(false);

        appointmentService.scheduleManual(
                "Dr. Lopez", UUID.randomUUID(), buildPatientInfo(),
                UUID.randomUUID(), "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, existingAppointments
        );

        verify(busySlotService).isBusy(existingAppointments, startTime, 30);
    }

    // ─────────────────────────────────────────────
    // scheduleManual — slot ocupado
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldThrowWhenSlotIsBusy() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(true);

        assertThatThrownBy(() -> appointmentService.scheduleManual(
                "Dr. Lopez", UUID.randomUUID(), buildPatientInfo(),
                UUID.randomUUID(), "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, List.of()
        ))
                .isInstanceOf(SlotNotAvailableException.class)
                .hasMessageContaining("ya está ocupado");
    }

    @Test
    void scheduleManualShouldNotCreateAppointmentWhenSlotIsBusy() {
        // Si el slot está ocupado, la cita nunca debe crearse — verificamos
        // que no se llama a ningún factory method del aggregate
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));

        when(busySlotService.isBusy(anyList(), eq(startTime), anyInt())).thenReturn(true);

        assertThatThrownBy(() -> appointmentService.scheduleManual(
                "Dr. Lopez", UUID.randomUUID(), buildPatientInfo(),
                UUID.randomUUID(), "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, List.of()
        )).isInstanceOf(SlotNotAvailableException.class);

        // slotTimeService no debe invocarse en este flujo
        verify(slotTimeService, never()).calculateAvailable(anyList(), anyList(), anyInt());
    }

    // ─────────────────────────────────────────────
    // scheduleAutonomous — slot libre
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldReturnAppointmentWhenSlotIsFree() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(false);

        Appointment result = appointmentService.scheduleAutonomous(
                "Dr. Lopez", idPatient, buildPatientInfo(),
                idDoctor, "Carlos Gomez", Specialty.QUIROPRAXIA,
                LocalDate.now().plusDays(1), startTime, 30, List.of()
        );

        assertThat(result).isNotNull();
        assertThat(result.getIdDoctor()).isEqualTo(idDoctor);
        assertThat(result.getIdPatient()).isEqualTo(idPatient);
        assertThat(result.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.AUTONOMO);
        assertThat(result.getSpecialty()).isEqualTo(Specialty.QUIROPRAXIA);
        assertThat(result.getStartTime()).isEqualTo(startTime);
    }

    @Test
    void scheduleAutonomousShouldDelegateBusyCheckToBusySlotService() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));
        List<Appointment> existingAppointments = List.of();

        when(busySlotService.isBusy(existingAppointments, startTime, 30)).thenReturn(false);

        appointmentService.scheduleAutonomous(
                "Dr. Lopez", UUID.randomUUID(), buildPatientInfo(),
                UUID.randomUUID(), "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, existingAppointments
        );

        verify(busySlotService).isBusy(existingAppointments, startTime, 30);
    }

    // ─────────────────────────────────────────────
    // scheduleAutonomous — slot ocupado
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldThrowWhenSlotIsBusy() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(true);

        assertThatThrownBy(() -> appointmentService.scheduleAutonomous(
                "Dr. Lopez", UUID.randomUUID(), buildPatientInfo(),
                UUID.randomUUID(), "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, List.of()
        ))
                .isInstanceOf(SlotNotAvailableException.class)
                .hasMessageContaining("ya está ocupado");
    }

    @Test
    void scheduleAutonomousShouldNotCreateAppointmentWhenSlotIsBusy() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));

        when(busySlotService.isBusy(anyList(), eq(startTime), anyInt())).thenReturn(true);

        assertThatThrownBy(() -> appointmentService.scheduleAutonomous(
                "Dr. Lopez", UUID.randomUUID(), buildPatientInfo(),
                UUID.randomUUID(), "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, List.of()
        )).isInstanceOf(SlotNotAvailableException.class);

        verify(slotTimeService, never()).calculateAvailable(anyList(), anyList(), anyInt());
    }

    // ─────────────────────────────────────────────
    // Diferencia clave entre Manual y Autónomo
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualAndAutonomousShouldDifferOnlyInSchedulingOrigin() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));

        when(busySlotService.isBusy(anyList(), eq(startTime), anyInt())).thenReturn(false);

        Appointment manual = appointmentService.scheduleManual(
                "Dr. Lopez", idPatient, buildPatientInfo(),
                idDoctor, "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, List.of()
        );
        Appointment autonomo = appointmentService.scheduleAutonomous(
                "Dr. Lopez", idPatient, buildPatientInfo(),
                idDoctor, "Carlos Gomez", Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1), startTime, 30, List.of()
        );

        assertThat(manual.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.MANUAL);
        assertThat(autonomo.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.AUTONOMO);
    }

    // ─────────────────────────────────────────────
    // getAvailableSlots — delegación a SlotTimeService
    // ─────────────────────────────────────────────

    @Test
    void getAvailableSlotsShouldReturnSlotsFromSlotTimeService() {
        List<AppointmentTime> doctorSlots = List.of(
                new AppointmentTime(LocalTime.of(7, 0)),
                new AppointmentTime(LocalTime.of(7, 30)),
                new AppointmentTime(LocalTime.of(8, 0))
        );
        List<AppointmentTime> expectedAvailable = List.of(
                new AppointmentTime(LocalTime.of(7, 0)),
                new AppointmentTime(LocalTime.of(8, 0))
        );
        List<Appointment> existingAppointments = List.of();

        when(slotTimeService.calculateAvailable(doctorSlots, existingAppointments, 30))
                .thenReturn(expectedAvailable);

        List<AppointmentTime> result = appointmentService.getAvailableSlots(
                doctorSlots, existingAppointments, 30
        );

        assertThat(result).isEqualTo(expectedAvailable);
    }

    @Test
    void getAvailableSlotsShouldDelegateToSlotTimeServiceWithCorrectArguments() {
        List<AppointmentTime> doctorSlots = List.of(
                new AppointmentTime(LocalTime.of(9, 0))
        );
        List<Appointment> existingAppointments = List.of();

        when(slotTimeService.calculateAvailable(doctorSlots, existingAppointments, 45))
                .thenReturn(List.of());

        appointmentService.getAvailableSlots(doctorSlots, existingAppointments, 45);

        verify(slotTimeService).calculateAvailable(doctorSlots, existingAppointments, 45);
    }

    @Test
    void getAvailableSlotsShouldReturnEmptyWhenAllSlotsAreOccupied() {
        List<AppointmentTime> doctorSlots = List.of(
                new AppointmentTime(LocalTime.of(9, 0)),
                new AppointmentTime(LocalTime.of(9, 30))
        );
        List<Appointment> existingAppointments = List.of();

        when(slotTimeService.calculateAvailable(doctorSlots, existingAppointments, 30))
                .thenReturn(List.of());

        List<AppointmentTime> result = appointmentService.getAvailableSlots(
                doctorSlots, existingAppointments, 30
        );

        assertThat(result).isEmpty();
    }

    @Test
    void getAvailableSlotsShouldReturnAllSlotsWhenNoneAreOccupied() {
        List<AppointmentTime> doctorSlots = List.of(
                new AppointmentTime(LocalTime.of(7, 0)),
                new AppointmentTime(LocalTime.of(7, 30))
        );
        List<Appointment> existingAppointments = List.of();

        when(slotTimeService.calculateAvailable(doctorSlots, existingAppointments, 30))
                .thenReturn(doctorSlots);

        List<AppointmentTime> result = appointmentService.getAvailableSlots(
                doctorSlots, existingAppointments, 30
        );

        assertThat(result).containsExactlyInAnyOrderElementsOf(doctorSlots);
    }

    // ─────────────────────────────────────────────
    // Fixture
    // ─────────────────────────────────────────────

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