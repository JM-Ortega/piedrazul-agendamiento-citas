package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSchedulingRequest;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentTest {

    private AppointmentSchedulingRequest buildRequest(UUID idDoctor, UUID idPatient, SpecialtyCode specialty,
            LocalDate date, AppointmentTime startTime) {
        return new AppointmentSchedulingRequest(idDoctor, idPatient, specialty, date, startTime);
    }

    private Appointment buildWithState(AppointmentState state) {
        return Appointment.reconstruct(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                SpecialtyCode.FISIOTERAPIA,
                state,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0)),
                SchedulingOrigin.MANUAL
        );
    }

    @Nested
    class FactoryMethodsTests {

        @Test
        void scheduleManualShouldCreateAppointmentInAgendadaStateWithManualOrigin() {
            UUID idDoctor = UUID.randomUUID();
            UUID idPatient = UUID.randomUUID();
            LocalDate date = LocalDate.now().plusDays(1);
            AppointmentTime startTime = new AppointmentTime(LocalTime.of(9, 0));
            AppointmentSchedulingRequest request = buildRequest(
                    idDoctor, idPatient, SpecialtyCode.FISIOTERAPIA, date, startTime);

            Appointment appointment = Appointment.scheduleManual(request);

            assertThat(appointment.getIdAppointment()).isNull();
            assertThat(appointment.getIdDoctor()).isEqualTo(idDoctor);
            assertThat(appointment.getIdPatient()).isEqualTo(idPatient);
            assertThat(appointment.getSpecialty()).isEqualTo(SpecialtyCode.FISIOTERAPIA);
            assertThat(appointment.getDate()).isEqualTo(date);
            assertThat(appointment.getStartTime()).isEqualTo(startTime);
            assertThat(appointment.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.MANUAL);
            assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        }

        @Test
        void scheduleManualShouldAllowNullIdPatient() {
            AppointmentSchedulingRequest request = buildRequest(
                    UUID.randomUUID(), null, SpecialtyCode.FISIOTERAPIA,
                    LocalDate.now().plusDays(1), new AppointmentTime(LocalTime.of(9, 0)));

            Appointment appointment = Appointment.scheduleManual(request);

            assertThat(appointment.getIdPatient()).isNull();
        }

        @Test
        void scheduleAutonomousShouldCreateAppointmentInAgendadaStateWithAutonomoOrigin() {
            UUID idDoctor = UUID.randomUUID();
            UUID idPatient = UUID.randomUUID();
            LocalDate date = LocalDate.now().plusDays(1);
            AppointmentTime startTime = new AppointmentTime(LocalTime.of(10, 0));
            AppointmentSchedulingRequest request = buildRequest(
                    idDoctor, idPatient, SpecialtyCode.QUIROPRAXIA, date, startTime);

            Appointment appointment = Appointment.scheduleAutonomous(request);

            assertThat(appointment.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.AUTONOMO);
            assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        }

        @Test
        void scheduleAutonomousShouldThrowWhenIdPatientIsNull() {
            AppointmentSchedulingRequest request = buildRequest(
                    UUID.randomUUID(), null, SpecialtyCode.FISIOTERAPIA,
                    LocalDate.now().plusDays(1), new AppointmentTime(LocalTime.of(9, 0)));

            assertThatThrownBy(() -> Appointment.scheduleAutonomous(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("obligatorio");
        }

        @Test
        void scheduleManualShouldThrowWhenIdDoctorIsNull() {
            AppointmentSchedulingRequest request = buildRequest(
                    null, UUID.randomUUID(), SpecialtyCode.FISIOTERAPIA,
                    LocalDate.now().plusDays(1), new AppointmentTime(LocalTime.of(9, 0)));

            assertThatThrownBy(() -> Appointment.scheduleManual(request))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void scheduleManualShouldThrowWhenSpecialtyIsNull() {
            AppointmentSchedulingRequest request = buildRequest(
                    UUID.randomUUID(), UUID.randomUUID(), null,
                    LocalDate.now().plusDays(1), new AppointmentTime(LocalTime.of(9, 0)));

            assertThatThrownBy(() -> Appointment.scheduleManual(request))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void scheduleManualShouldThrowWhenDateIsNull() {
            AppointmentSchedulingRequest request = buildRequest(
                    UUID.randomUUID(), UUID.randomUUID(), SpecialtyCode.FISIOTERAPIA,
                    null, new AppointmentTime(LocalTime.of(9, 0)));

            assertThatThrownBy(() -> Appointment.scheduleManual(request))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void scheduleManualShouldThrowWhenStartTimeIsNull() {
            AppointmentSchedulingRequest request = buildRequest(
                    UUID.randomUUID(), UUID.randomUUID(), SpecialtyCode.FISIOTERAPIA,
                    LocalDate.now().plusDays(1), null);

            assertThatThrownBy(() -> Appointment.scheduleManual(request))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class RegisterUnscheduledAttentionTests {

        @Test
        void shouldCreateAppointmentInAtendidaStateWithSinCitaOrigin() {
            UUID idDoctor = UUID.randomUUID();
            UUID idPatient = UUID.randomUUID();

            Appointment appointment = Appointment.registerUnscheduledAttention(
                    idDoctor, idPatient, SpecialtyCode.FISIOTERAPIA);

            assertThat(appointment.getIdDoctor()).isEqualTo(idDoctor);
            assertThat(appointment.getIdPatient()).isEqualTo(idPatient);
            assertThat(appointment.getSpecialty()).isEqualTo(SpecialtyCode.FISIOTERAPIA);
            assertThat(appointment.getDate()).isEqualTo(LocalDate.now());
            assertThat(appointment.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.SIN_CITA);
            assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.ATENDIDA);
        }

        @Test
        void shouldThrowWhenIdDoctorIsNull() {
            assertThatThrownBy(() -> Appointment.registerUnscheduledAttention(
                    null, UUID.randomUUID(), SpecialtyCode.FISIOTERAPIA))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldThrowWhenIdPatientIsNull() {
            assertThatThrownBy(() -> Appointment.registerUnscheduledAttention(
                    UUID.randomUUID(), null, SpecialtyCode.FISIOTERAPIA))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void shouldThrowWhenSpecialtyIsNull() {
            assertThatThrownBy(() -> Appointment.registerUnscheduledAttention(
                    UUID.randomUUID(), UUID.randomUUID(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class ReconstructTests {

        @Test
        void shouldPreserveGivenStateInsteadOfDefaultingToAgendada() {
            Appointment appointment = buildWithState(AppointmentState.CANCELADA);

            assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        }

        @Test
        void shouldExposeAllGivenFieldsThroughGetters() {
            UUID idAppointment = UUID.randomUUID();
            UUID idDoctor = UUID.randomUUID();
            UUID idPatient = UUID.randomUUID();
            LocalDate date = LocalDate.now().plusDays(2);
            AppointmentTime startTime = new AppointmentTime(LocalTime.of(11, 0));

            Appointment appointment = Appointment.reconstruct(
                    idAppointment, idDoctor, idPatient, SpecialtyCode.QUIROPRAXIA,
                    AppointmentState.ATENDIDA, date, startTime, SchedulingOrigin.AUTONOMO);

            assertThat(appointment.getIdAppointment()).isEqualTo(idAppointment);
            assertThat(appointment.getIdDoctor()).isEqualTo(idDoctor);
            assertThat(appointment.getIdPatient()).isEqualTo(idPatient);
            assertThat(appointment.getSpecialty()).isEqualTo(SpecialtyCode.QUIROPRAXIA);
            assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.ATENDIDA);
            assertThat(appointment.getDate()).isEqualTo(date);
            assertThat(appointment.getStartTime()).isEqualTo(startTime);
            assertThat(appointment.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.AUTONOMO);
        }
    }

    @Nested
    class StateTransitionTests {

        @Test
        void cancelShouldSetStateToCanceladaWhenCurrentStateIsAgendada() {
            Appointment appointment = buildWithState(AppointmentState.AGENDADA);

            appointment.cancel();

            assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        }

        @Test
        void cancelShouldThrowWhenCurrentStateIsNotAgendada() {
            Appointment appointment = buildWithState(AppointmentState.ATENDIDA);

            assertThatThrownBy(appointment::cancel)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void changeStateShouldUpdateStateWhenCurrentStateIsAgendada() {
            Appointment appointment = buildWithState(AppointmentState.AGENDADA);

            appointment.changeState(AppointmentState.ATENDIDA);

            assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.ATENDIDA);
        }

        @Test
        void changeStateShouldThrowWhenCurrentStateIsNotAgendada() {
            Appointment appointment = buildWithState(AppointmentState.CANCELADA);

            assertThatThrownBy(() -> appointment.changeState(AppointmentState.ATENDIDA))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
