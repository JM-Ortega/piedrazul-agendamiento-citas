package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentTest {

    // ─────────────────────────────────────────────
    // scheduleManual — creación válida
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldCreateAppointmentWithCorrectFields() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();

        Appointment appointment = Appointment.scheduleManual(
                "Dr. Lopez",
                idDoctor,
                idPatient,
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        );

        assertThat(appointment.getDoctorName()).isEqualTo("Dr. Lopez");
        assertThat(appointment.getIdDoctor()).isEqualTo(idDoctor);
        assertThat(appointment.getIdPatient()).isEqualTo(idPatient);
        assertThat(appointment.getPatientName()).isEqualTo("Carlos Gomez");
        assertThat(appointment.getSpecialty()).isEqualTo(Specialty.FISIOTERAPIA);
        assertThat(appointment.getStartTime()).isEqualTo(new AppointmentTime(LocalTime.of(9, 0)));
    }

    @Test
    void scheduleManualShouldSetInitialStateToAgendada() {
        Appointment appointment = buildManualAppointment(UUID.randomUUID(), UUID.randomUUID());

        assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
    }

    @Test
    void scheduleManualShouldSetSchedulingOriginToManual() {
        Appointment appointment = buildManualAppointment(UUID.randomUUID(), UUID.randomUUID());

        assertThat(appointment.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.MANUAL);
    }

    @Test
    void scheduleManualShouldSetIdAppointmentToNull() {
        // La JPA genera el id — el dominio no lo asigna
        Appointment appointment = buildManualAppointment(UUID.randomUUID(), UUID.randomUUID());

        assertThat(appointment.getIdAppointment()).isNull();
    }

    @Test
    void scheduleManualShouldAllowNullIdPatient() {
        // En agendamiento manual el paciente puede no tener cuenta
        Appointment appointment = buildManualAppointment(null, UUID.randomUUID());

        assertThat(appointment.getIdPatient()).isNull();
    }

    // ─────────────────────────────────────────────
    // scheduleManual — validaciones de campos obligatorios
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualShouldThrowWhenIdDoctorIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleManual(
                "Dr. Lopez",
                null,
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("médico es obligatorio");
    }

    @Test
    void scheduleManualShouldThrowWhenDoctorNameIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleManual(
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nombre del médico es obligatorio");
    }

    @Test
    void scheduleManualShouldThrowWhenPatientInfoIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleManual(
                "Dr. Lopez",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Carlos Gomez",
                null,
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("datos del paciente son obligatorios");
    }

    @Test
    void scheduleManualShouldThrowWhenSpecialtyIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleManual(
                "Dr. Lopez",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                null,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("especialidad es obligatoria");
    }

    @Test
    void scheduleManualShouldThrowWhenDateIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleManual(
                "Dr. Lopez",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                null,
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fecha es obligatoria");
    }

    @Test
    void scheduleManualShouldThrowWhenStartTimeIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleManual(
                "Dr. Lopez",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("hora es obligatoria");
    }

    // ─────────────────────────────────────────────
    // scheduleAutonomous — creación válida
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldCreateAppointmentWithCorrectFields() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();

        Appointment appointment = Appointment.scheduleAutonomous(
                "Dr. Lopez",
                idDoctor,
                idPatient,
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.QUIROPRAXIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(10, 0))
        );

        assertThat(appointment.getIdDoctor()).isEqualTo(idDoctor);
        assertThat(appointment.getIdPatient()).isEqualTo(idPatient);
        assertThat(appointment.getSpecialty()).isEqualTo(Specialty.QUIROPRAXIA);
        assertThat(appointment.getStartTime()).isEqualTo(new AppointmentTime(LocalTime.of(10, 0)));
    }

    @Test
    void scheduleAutonomousShouldSetInitialStateToAgendada() {
        Appointment appointment = buildAutonomousAppointment(UUID.randomUUID());

        assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
    }

    @Test
    void scheduleAutonomousShouldSetSchedulingOriginToAutonomo() {
        Appointment appointment = buildAutonomousAppointment(UUID.randomUUID());

        assertThat(appointment.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.AUTONOMO);
    }

    @Test
    void scheduleAutonomousShouldSetIdAppointmentToNull() {
        Appointment appointment = buildAutonomousAppointment(UUID.randomUUID());

        assertThat(appointment.getIdAppointment()).isNull();
    }

    // ─────────────────────────────────────────────
    // scheduleAutonomous — idPatient es obligatorio
    // ─────────────────────────────────────────────

    @Test
    void scheduleAutonomousShouldThrowWhenIdPatientIsNull() {
        // A diferencia de scheduleManual, aquí idPatient NO puede ser null
        assertThatThrownBy(() -> Appointment.scheduleAutonomous(
                "Dr. Lopez",
                UUID.randomUUID(),
                null,
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("pacienteId es obligatorio en agendamiento autónomo");
    }

    @Test
    void scheduleAutonomousShouldThrowWhenIdDoctorIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleAutonomous(
                "Dr. Lopez",
                null,
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("médico es obligatorio");
    }

    @Test
    void scheduleAutonomousShouldThrowWhenDoctorNameIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleAutonomous(
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("nombre del médico es obligatorio");
    }

    @Test
    void scheduleAutonomousShouldThrowWhenSpecialtyIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleAutonomous(
                "Dr. Lopez",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                null,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("especialidad es obligatoria");
    }

    @Test
    void scheduleAutonomousShouldThrowWhenDateIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleAutonomous(
                "Dr. Lopez",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                null,
                new AppointmentTime(LocalTime.of(9, 0))
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fecha es obligatoria");
    }

    @Test
    void scheduleAutonomousShouldThrowWhenStartTimeIsNull() {
        assertThatThrownBy(() -> Appointment.scheduleAutonomous(
                "Dr. Lopez",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                null
        ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("hora es obligatoria");
    }

    // ─────────────────────────────────────────────
    // Diferencia clave entre los dos factory methods
    // ─────────────────────────────────────────────

    @Test
    void scheduleManualAndAutonomousShouldDifferOnlyInOriginAndIdPatientRequirement() {
        UUID idDoctor  = UUID.randomUUID();
        UUID idPatient = UUID.randomUUID();
        PatientInfo info = buildPatientInfo();
        LocalDate date   = LocalDate.now().plusDays(1);
        AppointmentTime time = new AppointmentTime(LocalTime.of(9, 0));

        Appointment manual    = Appointment.scheduleManual(
                "Dr. Lopez", idDoctor, idPatient, "Carlos Gomez",
                info, Specialty.FISIOTERAPIA, date, time
        );
        Appointment autonomo  = Appointment.scheduleAutonomous(
                "Dr. Lopez", idDoctor, idPatient, "Carlos Gomez",
                info, Specialty.FISIOTERAPIA, date, time
        );

        assertThat(manual.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.MANUAL);
        assertThat(autonomo.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.AUTONOMO);

        // Ambos inician en AGENDADA
        assertThat(manual.getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        assertThat(autonomo.getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
    }

    // ─────────────────────────────────────────────
    // reconstruct — restauración desde BD
    // ─────────────────────────────────────────────

    @Test
    void reconstructShouldRestoreAppointmentWithGivenState() {
        // reconstruct es el único camino para asignar un estado distinto a AGENDADA
        UUID idAppointment = UUID.randomUUID();

        Appointment appointment = Appointment.reconstruct(
                idAppointment,
                UUID.randomUUID(),
                "Dr. Lopez",
                UUID.randomUUID(),
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.TERAPIA_NEURAL,
                AppointmentState.CANCELADA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(8, 0)),
                SchedulingOrigin.AUTONOMO
        );

        assertThat(appointment.getIdAppointment()).isEqualTo(idAppointment);
        assertThat(appointment.getAppointmentState()).isEqualTo(AppointmentState.CANCELADA);
        assertThat(appointment.getSchedulingOrigin()).isEqualTo(SchedulingOrigin.AUTONOMO);
    }

    @Test
    void reconstructShouldPreserveAllStatesCorrectly() {
        // Verificamos que reconstruct no fuerza AGENDADA para ningún estado
        for (AppointmentState state : AppointmentState.values()) {
            Appointment appointment = Appointment.reconstruct(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "Dr. Lopez",
                    UUID.randomUUID(),
                    "Carlos Gomez",
                    buildPatientInfo(),
                    Specialty.FISIOTERAPIA,
                    state,
                    LocalDate.now().plusDays(1),
                    new AppointmentTime(LocalTime.of(9, 0)),
                    SchedulingOrigin.MANUAL
            );

            assertThat(appointment.getAppointmentState()).isEqualTo(state);
        }
    }

    // ─────────────────────────────────────────────
    // AppointmentState — isActive()
    // ─────────────────────────────────────────────

    @Test
    void agendadaStateShouldBeActive() {
        assertThat(AppointmentState.AGENDADA.isActive()).isTrue();
    }

    @Test
    void reprogramadaStateShouldBeActive() {
        assertThat(AppointmentState.REPROGRAMADA.isActive()).isTrue();
    }

    @Test
    void canceladaStateShouldNotBeActive() {
        assertThat(AppointmentState.CANCELADA.isActive()).isFalse();
    }

    @Test
    void atendidaStateShouldNotBeActive() {
        assertThat(AppointmentState.ATENDIDA.isActive()).isFalse();
    }

    @Test
    void noAsistioStateShouldNotBeActive() {
        assertThat(AppointmentState.NO_ASISTIO.isActive()).isFalse();
    }

    // ─────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────

    private Appointment buildManualAppointment(UUID idPatient, UUID idDoctor) {
        return Appointment.scheduleManual(
                "Dr. Lopez",
                idDoctor,
                idPatient,
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
        );
    }

    private Appointment buildAutonomousAppointment(UUID idPatient) {
        return Appointment.scheduleAutonomous(
                "Dr. Lopez",
                UUID.randomUUID(),
                idPatient,
                "Carlos Gomez",
                buildPatientInfo(),
                Specialty.FISIOTERAPIA,
                LocalDate.now().plusDays(1),
                new AppointmentTime(LocalTime.of(9, 0))
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