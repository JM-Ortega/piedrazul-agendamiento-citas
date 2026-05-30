package co.edu.unicauca.piedrazul.backend.appointment.unit.domain.service;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BusySlotServiceTest {

    private BusySlotService busySlotService;

    @BeforeEach
    void setUp() {
        busySlotService = new BusySlotService();
    }

    // ─────────────────────────────────────────────
    // isBusy — lista vacía / sin citas
    // ─────────────────────────────────────────────

    @Test
    void isBusyShouldReturnFalseWhenThereAreNoExistingAppointments() {
        AppointmentTime newSlot = new AppointmentTime(LocalTime.of(9, 0));

        boolean result = busySlotService.isBusy(List.of(), newSlot, 30);

        assertThat(result).isFalse();
    }

    // ─────────────────────────────────────────────
    // isBusy — citas activas ocupan el slot
    // ─────────────────────────────────────────────

    @Test
    void isBusyShouldReturnTrueWhenActiveAppointmentCollidesWithNewSlot() {
        // Cita activa (AGENDADA) a las 9:00 — nuevo slot a las 9:00 → colisión exacta
        Appointment active = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.AGENDADA
        );
        AppointmentTime newSlot = new AppointmentTime(LocalTime.of(9, 0));

        boolean result = busySlotService.isBusy(List.of(active), newSlot, 30);

        assertThat(result).isTrue();
    }

    @Test
    void isBusyShouldReturnTrueWhenActiveAppointmentCollidesWithinInterval() {
        // Cita activa a las 9:00 — nuevo slot a las 9:15 — intervalo 30 min → colisión
        Appointment active = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.AGENDADA
        );
        AppointmentTime newSlot = new AppointmentTime(LocalTime.of(9, 15));

        boolean result = busySlotService.isBusy(List.of(active), newSlot, 30);

        assertThat(result).isTrue();
    }

    @Test
    void isBusyShouldReturnTrueWhenReprogramadaAppointmentCollidesWithNewSlot() {
        // REPROGRAMADA también es activa → debe bloquear el slot
        Appointment reprogramada = buildAppointmentWithState(
                LocalTime.of(10, 0), AppointmentState.REPROGRAMADA
        );
        AppointmentTime newSlot = new AppointmentTime(LocalTime.of(10, 0));

        boolean result = busySlotService.isBusy(List.of(reprogramada), newSlot, 30);

        assertThat(result).isTrue();
    }

    @Test
    void isBusyShouldReturnTrueWhenAtLeastOneActiveAppointmentCollidesAmongMany() {
        // Varias citas: una cancelada y una activa en el mismo horario → la activa bloquea
        Appointment cancelada = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.CANCELADA
        );
        Appointment activa = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.AGENDADA
        );
        AppointmentTime newSlot = new AppointmentTime(LocalTime.of(9, 0));

        boolean result = busySlotService.isBusy(List.of(cancelada, activa), newSlot, 30);

        assertThat(result).isTrue();
    }

    // ─────────────────────────────────────────────
    // isBusy — citas inactivas NO bloquean
    // ─────────────────────────────────────────────

    @Test
    void isBusyShouldReturnFalseWhenOnlyCanceladaAppointmentExistsAtSameTime() {
        Appointment cancelada = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.CANCELADA
        );
        AppointmentTime newSlot = new AppointmentTime(LocalTime.of(9, 0));

        boolean result = busySlotService.isBusy(List.of(cancelada), newSlot, 30);

        assertThat(result).isFalse();
    }

    @Test
    void isBusyShouldReturnFalseWhenOnlyAtendidaAppointmentExistsAtSameTime() {
        Appointment atendida = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.ATENDIDA
        );
        AppointmentTime newSlot = new AppointmentTime(LocalTime.of(9, 0));

        boolean result = busySlotService.isBusy(List.of(atendida), newSlot, 30);

        assertThat(result).isFalse();
    }

    @Test
    void isBusyShouldReturnFalseWhenOnlyNoAsistioAppointmentExistsAtSameTime() {
        Appointment noAsistio = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.NO_ASISTIO
        );
        AppointmentTime newSlot = new AppointmentTime(LocalTime.of(9, 0));

        boolean result = busySlotService.isBusy(List.of(noAsistio), newSlot, 30);

        assertThat(result).isFalse();
    }

    // ─────────────────────────────────────────────
    // isBusy — el nuevo slot está libre (diferencia >= intervalo)
    // ─────────────────────────────────────────────

    @Test
    void isBusyShouldReturnFalseWhenActiveAppointmentDoesNotCollideWithNewSlot() {
        // Cita activa a las 9:00 — nuevo slot a las 9:30 — intervalo 30 → no colisiona
        Appointment active = buildAppointmentWithState(
                LocalTime.of(9, 0), AppointmentState.AGENDADA
        );
        AppointmentTime newSlot = new AppointmentTime(LocalTime.of(9, 30));

        boolean result = busySlotService.isBusy(List.of(active), newSlot, 30);

        assertThat(result).isFalse();
    }

    // ─────────────────────────────────────────────
    // Fixture helper
    // ─────────────────────────────────────────────

    /**
     * Construye un Appointment con el estado y la hora indicados usando
     * reconstruct(), el único factory method que permite asignar un estado
     * diferente a AGENDADA desde fuera del dominio.
     */
    private Appointment buildAppointmentWithState(LocalTime time, AppointmentState state) {
        PatientInfo patientInfo = PatientInfo.of(
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

        return Appointment.reconstruct(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Dra. Lopez",
                UUID.randomUUID(),
                "Carlos Gomez",
                patientInfo,
                Specialty.FISIOTERAPIA,
                state,
                LocalDate.now().plusDays(1),
                new AppointmentTime(time),
                SchedulingOrigin.MANUAL
        );
    }
}