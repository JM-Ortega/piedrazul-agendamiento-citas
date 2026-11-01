package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentStateTest {

    @Test
    void isBussyShouldReturnTrueForAgendada() {
        assertThat(AppointmentState.AGENDADA.isBussy()).isTrue();
    }

    @Test
    void isBussyShouldReturnTrueForAtendida() {
        assertThat(AppointmentState.ATENDIDA.isBussy()).isTrue();
    }

    @Test
    void isBussyShouldReturnFalseForCancelada() {
        assertThat(AppointmentState.CANCELADA.isBussy()).isFalse();
    }

    @Test
    void isBussyShouldReturnFalseForNoAsistio() {
        assertThat(AppointmentState.NO_ASISTIO.isBussy()).isFalse();
    }

    @Test
    void isBussyShouldReturnFalseForReprogramada() {
        assertThat(AppointmentState.REPROGRAMADA.isBussy()).isFalse();
    }
}
