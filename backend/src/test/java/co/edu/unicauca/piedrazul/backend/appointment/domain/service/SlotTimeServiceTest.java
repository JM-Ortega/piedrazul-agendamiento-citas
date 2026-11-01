package co.edu.unicauca.piedrazul.backend.appointment.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AvailableDateSlots;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingDateSlots;
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
    // Sin citas existentes — todas las franjas del médico están disponibles
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldReturnAllSlotsWhenNoAppointmentsExist() {
        LocalDate date = LocalDate.now().plusDays(1);
        WorkingDateSlots workingDateSlots = new WorkingDateSlots(
                date, List.of(LocalTime.of(7, 0), LocalTime.of(7, 30), LocalTime.of(8, 0)));

        List<AvailableDateSlots> available = slotTimeService.calculateAvailable(
                List.of(workingDateSlots), List.of(), 30
        );

        assertThat(available).containsExactly(
                new AvailableDateSlots(date, List.of(LocalTime.of(7, 0), LocalTime.of(7, 30), LocalTime.of(8, 0)))
        );
    }

    // ─────────────────────────────────────────────
    // Sin franjas del médico — resultado siempre vacío
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldReturnEmptyWhenDoctorHasNoSlots() {
        List<AvailableDateSlots> available = slotTimeService.calculateAvailable(
                List.of(), List.of(), 30
        );

        assertThat(available).isEmpty();
    }

    // ─────────────────────────────────────────────
    // Cita activa bloquea exactamente su franja
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldExcludeSlotOccupiedByActiveAppointment() {
        LocalDate date = LocalDate.now().plusDays(1);
        LocalTime slotAt7 = LocalTime.of(7, 0);
        LocalTime slotAt730 = LocalTime.of(7, 30);
        LocalTime slotAt8 = LocalTime.of(8, 0);
        WorkingDateSlots workingDateSlots = new WorkingDateSlots(date, List.of(slotAt7, slotAt730, slotAt8));

        // Cita activa ocupa las 7:30
        Appointment cita730 = buildAppointmentWithState(date, LocalTime.of(7, 30), AppointmentState.AGENDADA);

        List<AvailableDateSlots> available = slotTimeService.calculateAvailable(
                List.of(workingDateSlots), List.of(cita730), 30
        );

        assertThat(available).hasSize(1);
        assertThat(available.getFirst().availableSlots())
                .containsExactlyInAnyOrder(slotAt7, slotAt8)
                .doesNotContain(slotAt730);
    }

    @Test
    void calculateAvailableShouldExcludeSlotOccupiedByAtendidaAppointment() {
        // ATENDIDA también se considera ocupante del slot (igual que AGENDADA)
        LocalDate date = LocalDate.now().plusDays(1);
        LocalTime slotAt9 = LocalTime.of(9, 0);
        WorkingDateSlots workingDateSlots = new WorkingDateSlots(date, List.of(slotAt9));

        Appointment atendida = buildAppointmentWithState(date, LocalTime.of(9, 0), AppointmentState.ATENDIDA);

        List<AvailableDateSlots> available = slotTimeService.calculateAvailable(
                List.of(workingDateSlots), List.of(atendida), 30
        );

        assertThat(available).isEmpty();
    }

    @Test
    void calculateAvailableShouldExcludeSlotCollidingWithinInterval() {
        // Médico tiene franja a las 9:15 — cita activa a las 9:00 — intervalo 30 min
        // 9:15 - 9:00 = 15 min < 30 → franja bloqueada
        LocalDate date = LocalDate.now().plusDays(1);
        LocalTime slotAt9 = LocalTime.of(9, 0);
        LocalTime slotAt915 = LocalTime.of(9, 15);
        LocalTime slotAt930 = LocalTime.of(9, 30);
        WorkingDateSlots workingDateSlots = new WorkingDateSlots(date, List.of(slotAt9, slotAt915, slotAt930));

        Appointment citaAt9 = buildAppointmentWithState(date, LocalTime.of(9, 0), AppointmentState.AGENDADA);

        List<AvailableDateSlots> available = slotTimeService.calculateAvailable(
                List.of(workingDateSlots), List.of(citaAt9), 30
        );

        // 9:00 bloqueada por colisión exacta, 9:15 bloqueada por intervalo, 9:30 libre
        assertThat(available).hasSize(1);
        assertThat(available.getFirst().availableSlots())
                .containsExactly(slotAt930);
    }

    // ─────────────────────────────────────────────
    // Cita inactiva NO bloquea la franja
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldNotExcludeSlotOccupiedOnlyByCanceledAppointment() {
        LocalDate date = LocalDate.now().plusDays(1);
        LocalTime slotAt9 = LocalTime.of(9, 0);
        WorkingDateSlots workingDateSlots = new WorkingDateSlots(date, List.of(slotAt9));

        Appointment cancelada = buildAppointmentWithState(date, LocalTime.of(9, 0), AppointmentState.CANCELADA);

        List<AvailableDateSlots> available = slotTimeService.calculateAvailable(
                List.of(workingDateSlots), List.of(cancelada), 30
        );

        assertThat(available).containsExactly(new AvailableDateSlots(date, List.of(slotAt9)));
    }

    // ─────────────────────────────────────────────
    // Todas las franjas de la fecha ocupadas — la fecha desaparece del resultado
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldOmitDateEntirelyWhenAllItsSlotsAreOccupied() {
        LocalDate date = LocalDate.now().plusDays(1);
        LocalTime slotAt7 = LocalTime.of(7, 0);
        LocalTime slotAt730 = LocalTime.of(7, 30);
        WorkingDateSlots workingDateSlots = new WorkingDateSlots(date, List.of(slotAt7, slotAt730));

        Appointment cita7 = buildAppointmentWithState(date, LocalTime.of(7, 0), AppointmentState.AGENDADA);
        Appointment cita730 = buildAppointmentWithState(date, LocalTime.of(7, 30), AppointmentState.AGENDADA);

        List<AvailableDateSlots> available = slotTimeService.calculateAvailable(
                List.of(workingDateSlots), List.of(cita7, cita730), 30
        );

        // No debe aparecer ni siquiera como AvailableDateSlots con lista vacía
        assertThat(available).isEmpty();
    }

    @Test
    void calculateAvailableShouldBlockSlotWhenActiveAndCanceledAppointmentsCoexist() {
        LocalDate date = LocalDate.now().plusDays(1);
        LocalTime slotAt9 = LocalTime.of(9, 0);
        WorkingDateSlots workingDateSlots = new WorkingDateSlots(date, List.of(slotAt9));

        Appointment cancelada = buildAppointmentWithState(date, LocalTime.of(9, 0), AppointmentState.CANCELADA);
        Appointment activa = buildAppointmentWithState(date, LocalTime.of(9, 0), AppointmentState.AGENDADA);

        List<AvailableDateSlots> available = slotTimeService.calculateAvailable(
                List.of(workingDateSlots), List.of(cancelada, activa), 30
        );

        assertThat(available).isEmpty();
    }

    // ─────────────────────────────────────────────
    // Agrupamiento por fecha — una fecha ocupada no afecta a las demás
    // ─────────────────────────────────────────────

    @Test
    void calculateAvailableShouldKeepOtherDatesUnaffectedWhenAppointmentOnlyBlocksOneDate() {
        LocalDate busyDate = LocalDate.now().plusDays(1);
        LocalDate freeDate = busyDate.plusDays(1);
        LocalTime slot = LocalTime.of(9, 0);

        WorkingDateSlots busyDateSlots = new WorkingDateSlots(busyDate, List.of(slot));
        WorkingDateSlots freeDateSlots = new WorkingDateSlots(freeDate, List.of(slot));

        Appointment appointmentOnBusyDate = buildAppointmentWithState(busyDate, slot, AppointmentState.AGENDADA);

        List<AvailableDateSlots> available = slotTimeService.calculateAvailable(
                List.of(busyDateSlots, freeDateSlots), List.of(appointmentOnBusyDate), 30
        );

        // La fecha ocupada desaparece del resultado; la fecha libre conserva su franja intacta
        assertThat(available).containsExactly(new AvailableDateSlots(freeDate, List.of(slot)));
    }

    // ─────────────────────────────────────────────
    // Fixture helper
    // ─────────────────────────────────────────────

    private Appointment buildAppointmentWithState(LocalDate date, LocalTime time, AppointmentState state) {
        return Appointment.reconstruct(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpecialtyCode.FISIOTERAPIA,
                state,
                date,
                new AppointmentTime(time),
                SchedulingOrigin.MANUAL
        );
    }
}
