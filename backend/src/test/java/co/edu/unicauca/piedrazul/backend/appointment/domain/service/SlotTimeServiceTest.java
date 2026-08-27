package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlotTimeServiceTest {

    private SlotTimeService slotTimeService;

    @BeforeEach
    void setUp() {
        // SlotTimeService depende de BusySlotService — usamos la implementación real
        // porque BusySlotService es pura lógica de dominio sin dependencias externas
        slotTimeService = new SlotTimeService(new BusySlotService());
    }

    // ─────────────────────────────────────────────
    // Sin citas existentes — todos los slots del médico están disponibles
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldReturnAllSlotsWhenNoAppointmentsExist() {
        List<AppointmentTime> doctorSlots = List.of(
                new AppointmentTime(LocalTime.of(7, 0)),
                new AppointmentTime(LocalTime.of(7, 30)),
                new AppointmentTime(LocalTime.of(8, 0))
        );

        List<AppointmentTime> available = slotTimeService.calculateAvailable(
                doctorSlots, List.of(), 30
        );

        assertThat(available).containsExactlyInAnyOrderElementsOf(doctorSlots);
    }

    // ─────────────────────────────────────────────
    // Sin franjas del médico — resultado siempre vacío
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldReturnEmptyWhenDoctorHasNoSlots() {
        List<AppointmentTime> available = slotTimeService.calculateAvailable(
                List.of(), List.of(), 30
        );

        assertThat(available).isEmpty();
    }

    // ─────────────────────────────────────────────
    // Cita activa bloquea exactamente su franja
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldExcludeSlotOccupiedByActiveAppointment() {
        AppointmentTime slotAt7   = new AppointmentTime(LocalTime.of(7, 0));
        AppointmentTime slotAt730 = new AppointmentTime(LocalTime.of(7, 30));
        AppointmentTime slotAt8   = new AppointmentTime(LocalTime.of(8, 0));

        List<AppointmentTime> doctorSlots = List.of(slotAt7, slotAt730, slotAt8);

        // Cita activa ocupa las 7:30
        Appointment cita730 = buildAppointmentWithState(
                LocalTime.of(7, 30), AppointmentState.AGENDADA
        );

        List<AppointmentTime> available = slotTimeService.calculateAvailable(
                doctorSlots, List.of(cita730), 30
        );

        assertThat(available)
                .containsExactlyInAnyOrder(slotAt7, slotAt8)
                .doesNotContain(slotAt730);
    }

    @Test
    void calculateAvailableShouldExcludeSlotCollidingWithinInterval() {
        // Médico tiene franja a las 9:15 — cita activa a las 9:00 — intervalo 30 min
        // 9:15 - 9:00 = 15 min < 30 → franja bloqueada
        AppointmentTime slotAt9   = new AppointmentTime(LocalTime.of(9, 0));
        AppointmentTime slotAt915 = new AppointmentTime(LocalTime.of(9, 15));
        AppointmentTime slotAt930 = new AppointmentTime(LocalTime.of(9, 30));

        List<AppointmentTime> doctorSlots = List.of(slotAt9, slotAt915, slotAt930);

        Appointment citaAt9 = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.AGENDADA
        );

        List<AppointmentTime> available = slotTimeService.calculateAvailable(
                doctorSlots, List.of(citaAt9), 30
        );

        // 9:00 bloqueada por colisión exacta, 9:15 bloqueada por intervalo, 9:30 libre
        assertThat(available)
                .containsExactly(slotAt930)
                .doesNotContain(slotAt9, slotAt915);
    }

    // ─────────────────────────────────────────────
    // Cita inactiva NO bloquea la franja
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldNotExcludeSlotOccupiedOnlyByCanceledAppointment() {
        AppointmentTime slotAt9 = new AppointmentTime(LocalTime.of(9, 0));
        List<AppointmentTime> doctorSlots = List.of(slotAt9);

        Appointment cancelada = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.CANCELADA
        );

        List<AppointmentTime> available = slotTimeService.calculateAvailable(
                doctorSlots, List.of(cancelada), 30
        );

        assertThat(available).containsExactly(slotAt9);
    }

    @Test
    void calculateAvailableShouldNotExcludeSlotOccupiedOnlyByAtendidaAppointment() {
        AppointmentTime slotAt9 = new AppointmentTime(LocalTime.of(9, 0));
        List<AppointmentTime> doctorSlots = List.of(slotAt9);

        Appointment atendida = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.ATENDIDA
        );

        List<AppointmentTime> available = slotTimeService.calculateAvailable(
                doctorSlots, List.of(atendida), 30
        );

        assertThat(available).containsExactly(slotAt9);
    }

    // ─────────────────────────────────────────────
    // Todas las franjas ocupadas
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldReturnEmptyWhenAllSlotsAreOccupied() {
        AppointmentTime slotAt7   = new AppointmentTime(LocalTime.of(7, 0));
        AppointmentTime slotAt730 = new AppointmentTime(LocalTime.of(7, 30));

        List<AppointmentTime> doctorSlots = List.of(slotAt7, slotAt730);

        Appointment cita7   = buildAppointmentWithState(LocalTime.of(7, 0),  AppointmentState.AGENDADA);
        Appointment cita730 = buildAppointmentWithState(LocalTime.of(7, 30), AppointmentState.AGENDADA);

        List<AppointmentTime> available = slotTimeService.calculateAvailable(
                doctorSlots, List.of(cita7, cita730), 30
        );

        assertThat(available).isEmpty();
    }

    // ─────────────────────────────────────────────
    // Cita activa y cancelada en el mismo horario — la activa sigue bloqueando
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldBlockSlotWhenActiveAndCanceledAppointmentsCoexist() {
        AppointmentTime slotAt9 = new AppointmentTime(LocalTime.of(9, 0));
        List<AppointmentTime> doctorSlots = List.of(slotAt9);

        Appointment cancelada = buildAppointmentWithState(LocalTime.of(9, 0), AppointmentState.CANCELADA);
        Appointment activa    = buildAppointmentWithState(LocalTime.of(9, 0), AppointmentState.AGENDADA);

        List<AppointmentTime> available = slotTimeService.calculateAvailable(
                doctorSlots, List.of(cancelada, activa), 30
        );

        assertThat(available).isEmpty();
    }

    // ─────────────────────────────────────────────
    // Fixture helper
    // ─────────────────────────────────────────────

    private Appointment buildAppointmentWithState(LocalTime time, AppointmentState state) {
        return Appointment.reconstruct(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpecialtyCode.FISIOTERAPIA,
                state,
                LocalDate.now().plusDays(1),
                new AppointmentTime(time),
                SchedulingOrigin.MANUAL
        );
    }
}