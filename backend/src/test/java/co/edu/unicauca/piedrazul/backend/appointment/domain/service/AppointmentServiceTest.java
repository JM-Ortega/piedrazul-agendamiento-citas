package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.SlotNotAvailableException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSchedulingRequest;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    @Mock
    private BusySlotService busySlotService;

    private AppointmentService appointmentService;

    @BeforeEach
    void setUp() {
                appointmentService = new AppointmentService(busySlotService);
    }

    // ─────────────────────────────────────────────
    // scheduleManual — slot libre
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldReturnAppointmentWhenSlotIsFree() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentSchedulingRequest request = buildRequest(
                idDoctor,
                idPatient,
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(false);

        Appointment result = appointmentService.scheduleManual(request, 30, List.of());

        assertThat(result).isNotNull();
        assertThat(result.getIdDoctor()).isEqualTo(idDoctor);
        assertThat(result.getIdPatient()).isEqualTo(idPatient);
        assertThat(result.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.MANUAL);
        assertThat(result.getSpecialty()).isEqualTo(SpecialtyCode.FISIOTERAPIA);
        assertThat(result.getStartTime()).isEqualTo(startTime);
    }

    @Test
    void scheduleManualShouldAllowNullIdPatient() {
        // En manual el paciente puede no tener cuenta en el sistema
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentSchedulingRequest request = buildRequest(
                UUID.randomUUID(),
                null,
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(false);

        Appointment result = appointmentService.scheduleManual(request, 30, List.of());

        assertThat(result.getIdPatient()).isNull();
    }

    @Test
    void scheduleManualShouldDelegateBusyCheckToBusySlotService() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        List<Appointment> existingAppointments = List.of();
        AppointmentSchedulingRequest request = buildRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(existingAppointments, startTime, 30)).thenReturn(false);

        appointmentService.scheduleManual(request, 30, existingAppointments);

        verify(busySlotService).isBusy(existingAppointments, startTime, 30);
    }

    // ─────────────────────────────────────────────
    // scheduleManual — slot ocupado
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldThrowWhenSlotIsBusy() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentSchedulingRequest request = buildRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(true);

        assertThatThrownBy(() -> appointmentService.scheduleManual(request, 30, List.of()))
                .isInstanceOf(SlotNotAvailableException.class)
                .hasMessageContaining("ya está ocupado");
    }

    @Test
    void scheduleManualShouldNotCreateAppointmentWhenSlotIsBusy() {
        // Si el slot está ocupado, la cita nunca debe crearse — verificamos
        // que no se llama a ningún factory method del aggregate
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentSchedulingRequest request = buildRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(anyList(), eq(startTime), anyInt())).thenReturn(true);

        assertThatThrownBy(() -> appointmentService.scheduleManual(request, 30, List.of()))
                .isInstanceOf(SlotNotAvailableException.class);
    }

    // ─────────────────────────────────────────────
    // scheduleAutonomous — slot libre
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldReturnAppointmentWhenSlotIsFree() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));
        AppointmentSchedulingRequest request = buildRequest(
                idDoctor,
                idPatient,
                SpecialtyCode.QUIROPRAXIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(false);

        Appointment result = appointmentService.scheduleAutonomous(request, 30, List.of());

        assertThat(result).isNotNull();
        assertThat(result.getIdDoctor()).isEqualTo(idDoctor);
        assertThat(result.getIdPatient()).isEqualTo(idPatient);
        assertThat(result.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.AUTONOMO);
        assertThat(result.getSpecialty()).isEqualTo(SpecialtyCode.QUIROPRAXIA);
        assertThat(result.getStartTime()).isEqualTo(startTime);
    }

    @Test
    void scheduleAutonomousShouldDelegateBusyCheckToBusySlotService() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));
        List<Appointment> existingAppointments = List.of();
        AppointmentSchedulingRequest request = buildRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(existingAppointments, startTime, 30)).thenReturn(false);

        appointmentService.scheduleAutonomous(request, 30, existingAppointments);

        verify(busySlotService).isBusy(existingAppointments, startTime, 30);
    }

    // ─────────────────────────────────────────────
    // scheduleAutonomous — slot ocupado
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldThrowWhenSlotIsBusy() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));
        AppointmentSchedulingRequest request = buildRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(true);

        assertThatThrownBy(() -> appointmentService.scheduleAutonomous(request, 30, List.of()))
                .isInstanceOf(SlotNotAvailableException.class)
                .hasMessageContaining("ya está ocupado");
    }

    @Test
    void scheduleAutonomousShouldNotCreateAppointmentWhenSlotIsBusy() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));
        AppointmentSchedulingRequest request = buildRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(anyList(), eq(startTime), anyInt())).thenReturn(true);

        assertThatThrownBy(() -> appointmentService.scheduleAutonomous(request, 30, List.of()))
                .isInstanceOf(SlotNotAvailableException.class);
    }

    @Test
    void scheduleAutonomousShouldThrowWhenIdPatientIsNull() {
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));
        AppointmentSchedulingRequest request = buildRequest(
                UUID.randomUUID(),
                null,
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(anyList(), eq(startTime), eq(30))).thenReturn(false);

        assertThatThrownBy(() -> appointmentService.scheduleAutonomous(request, 30, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obligatorio");
    }

    // ─────────────────────────────────────────────
    // Diferencia clave entre Manual y Autónomo
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualAndAutonomousShouldDifferOnlyInSchedulingOrigin() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentSchedulingRequest request = buildRequest(
                idDoctor,
                idPatient,
                SpecialtyCode.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                startTime
        );

        when(busySlotService.isBusy(anyList(), eq(startTime), anyInt())).thenReturn(false);

        Appointment manual = appointmentService.scheduleManual(request, 30, List.of());
        Appointment autonomo = appointmentService.scheduleAutonomous(request, 30, List.of());

        assertThat(manual.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.MANUAL);
        assertThat(autonomo.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.AUTONOMO);
    }

    // ─────────────────────────────────────────────
    // Fixture
    // ─────────────────────────────────────────────



        private AppointmentSchedulingRequest buildRequest(
                        UUID idDoctor,
                        UUID idPatient,
                        SpecialtyCode specialty,
                        LocalDate date,
                        AppointmentTime startTime
        ) {
                return new AppointmentSchedulingRequest(
                                idDoctor,
                                idPatient,
                                specialty,
                                date,
                                startTime
                );
        }
}