package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentStateConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.OnlyOneAppointmentPerMonthException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.PatientAlreadyScheduledInSpecialtyException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.PatientScheduleTimeConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.SlotNotAvailableException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integridad del agendamiento y de las transiciones de estado bajo concurrencia real, sobre el
 * servicio Spring real (con su configuración transaccional) y PostgreSQL 16 migrado con Flyway.
 * Ver {@link AppointmentConcurrencyITSupport} para la técnica de pausa determinista.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AppointmentConcurrencyIT extends AppointmentConcurrencyITSupport {

    private static final SpecialtyCode NEURAL = SpecialtyCode.TERAPIA_NEURAL;
    private static final LocalDate DAY = LocalDate.now().plusDays(3);

    @Autowired
    private MockMvc mockMvc;

    record Race(Throwable holder, Throwable competitor) {
    }

    /**
     * {@code holder} entra en su sección crítica y se detiene; se lanza {@code competitor}, se comprueba que
     * PostgreSQL lo tiene esperando un lock, y se libera a {@code holder}.
     */
    private Race race(Callable<?> holder, Callable<?> competitor, Set<String> waitEvents) throws Exception {
        Pause pause = new Pause(Phase.BEFORE_SAVE);
        Future<?> holderFuture = runPaused(pause, holder);
        pause.awaitReached();
        Future<?> competitorFuture = run(competitor);
        awaitBlockedOnLock(competitorFuture, waitEvents);
        pause.release();
        return new Race(outcome(holderFuture), outcome(competitorFuture));
    }

    /** El perdedor debe recibir lo mismo que recibiría la misma petición ejecutada en secuencia. */
    private void assertSameAsSequential(Throwable loser, Callable<?> sameRequest) {
        assertThatThrownBy(sameRequest::call).isInstanceOf(loser.getClass()).hasMessage(loser.getMessage());
    }

    // =================================================================== reservas: doctor

    @Test
    void sameDoctorSameSlot_onlyOneBookingCommits() throws Exception {
        UUID doctor = newDoctor();
        UUID p1 = newPatient();
        UUID p2 = newPatient();
        Callable<Void> second = bookManual(doctor, p2, NEURAL, DAY, LocalTime.of(9, 0));

        Race race = race(bookManual(doctor, p1, NEURAL, DAY, LocalTime.of(9, 0)), second, ADVISORY);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(SlotNotAvailableException.class);
        assertSameAsSequential(race.competitor(), second);
        assertThat(appointmentsOf(doctor, DAY)).hasSize(1);
        assertThat(appointmentsOf(doctor, DAY).getFirst().getIdPatient()).isEqualTo(p1);
        assertCommittedEventsMatch(doctor, DAY);
    }

    @Test
    void partiallyOverlappingStarts_onlyOneBookingCommits() throws Exception {
        UUID doctor = newDoctor(); // intervalo configurado: 30 min
        UUID p1 = newPatient();
        UUID p2 = newPatient();
        Callable<Void> overlapping = bookManual(doctor, p2, NEURAL, DAY, LocalTime.of(9, 15));

        Race race = race(bookManual(doctor, p1, NEURAL, DAY, LocalTime.of(9, 0)), overlapping, ADVISORY);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(SlotNotAvailableException.class);
        assertSameAsSequential(race.competitor(), overlapping);
        assertThat(appointmentsOf(doctor, DAY)).extracting(AppointmentEntity::getStartTime)
                .containsExactly(LocalTime.of(9, 0));
        assertCommittedEventsMatch(doctor, DAY);
    }

    @Test
    void adjacentSlots_bothBookingsCommit_theSecondOneWaitingForTheFirst() throws Exception {
        UUID doctor = newDoctor();
        UUID p1 = newPatient();
        UUID p2 = newPatient();

        Race race = race(bookManual(doctor, p1, NEURAL, DAY, LocalTime.of(9, 0)),
                bookManual(doctor, p2, NEURAL, DAY, LocalTime.of(9, 30)), ADVISORY);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isNull();
        assertThat(appointmentsOf(doctor, DAY)).extracting(AppointmentEntity::getStartTime)
                .containsExactlyInAnyOrder(LocalTime.of(9, 0), LocalTime.of(9, 30));
        assertCommittedEventsMatch(doctor, DAY);
    }

    // =================================================================== reservas: paciente

    @Test
    void samePatientAtTheSameTimeWithDifferentDoctors_onlyOneBookingCommits() throws Exception {
        UUID doctorA = newDoctor();
        UUID doctorB = newDoctor();
        UUID patient = newPatient();
        Callable<Void> conflicting = bookManual(doctorB, patient, NEURAL, DAY, LocalTime.of(9, 0));

        // Doctores distintos: solo el lock del paciente puede serializarlas.
        Race race = race(bookManual(doctorA, patient, NEURAL, DAY, LocalTime.of(9, 0)), conflicting, ADVISORY);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(PatientScheduleTimeConflictException.class);
        assertSameAsSequential(race.competitor(), conflicting);
        assertThat(appointmentJpaRepository.findByIdPatient(patient)).hasSize(1);
        assertThat(appointmentsOf(doctorB, DAY)).isEmpty();
        assertCommittedEventsMatch(doctorA, DAY);
        assertCommittedEventsMatch(doctorB, DAY);
    }

    // =================================================================== reservas autónomas

    @Test
    void autonomousDoubleSubmission_onlyOneBookingCommits() throws Exception {
        UUID doctor = newDoctor();
        UUID patient = newPatient();
        Callable<Void> duplicate = bookAutonomous(doctor, patient, NEURAL, DAY, LocalTime.of(9, 0));

        Race race = race(bookAutonomous(doctor, patient, NEURAL, DAY, LocalTime.of(9, 0)), duplicate, ADVISORY);

        assertThat(race.holder()).isNull();
        // Igual que en secuencia: la regla de especialidad se evalúa antes que el slot del doctor.
        assertThat(race.competitor()).isInstanceOf(PatientAlreadyScheduledInSpecialtyException.class);
        assertSameAsSequential(race.competitor(), duplicate);
        assertThat(appointmentsOf(doctor, DAY)).hasSize(1);
        assertCommittedEventsMatch(doctor, DAY);
    }

    @Test
    void autonomousSameSpecialtyWithDifferentDoctors_onlyOneBookingCommits() throws Exception {
        UUID doctorA = newDoctor();
        UUID doctorB = newDoctor();
        UUID patient = newPatient();
        Callable<Void> other = bookAutonomous(doctorB, patient, NEURAL, DAY, LocalTime.of(10, 0));

        Race race = race(bookAutonomous(doctorA, patient, NEURAL, DAY, LocalTime.of(9, 0)), other, ADVISORY);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(PatientAlreadyScheduledInSpecialtyException.class);
        assertSameAsSequential(race.competitor(), other);
        assertThat(appointmentJpaRepository.findByIdPatient(patient)).hasSize(1);
        assertCommittedEventsMatch(doctorB, DAY);
    }

    @Test
    void autonomousMonthlyQuota_onlyOneBookingCommits() throws Exception {
        UUID doctorA = newDoctor();
        UUID doctorB = newDoctor();
        UUID patient = newPatient();
        // Paciente "no nuevo" (ya tiene una cita ATENDIDA) para poder pedir otras especialidades.
        seedAppointment(doctorA, patient, NEURAL, AppointmentState.ATENDIDA, LocalDate.now().minusDays(40),
                LocalTime.of(8, 0), SchedulingOrigin.MANUAL);
        LocalDate thisMonth = LocalDate.now(); // el cupo mensual se evalúa sobre el mes calendario actual
        Callable<Void> secondInMonth = bookAutonomous(doctorB, patient, SpecialtyCode.QUIROPRAXIA, thisMonth,
                LocalTime.of(10, 0));

        Race race = race(bookAutonomous(doctorA, patient, SpecialtyCode.FISIOTERAPIA, thisMonth, LocalTime.of(9, 0)),
                secondInMonth, ADVISORY);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(OnlyOneAppointmentPerMonthException.class);
        assertSameAsSequential(race.competitor(), secondInMonth);
        assertThat(appointmentJpaRepository.findByIdPatient(patient))
                .filteredOn(a -> a.getSchedulingOrigin() == SchedulingOrigin.AUTONOMO).hasSize(1);
        assertCommittedEventsMatch(doctorB, thisMonth);
    }

    // =================================================================== manual vs autónomo (reglas asimétricas)

    @Test
    void manualBookingWinningFirst_blocksTheAutonomousBookingOfTheSameSpecialty() throws Exception {
        UUID doctorA = newDoctor();
        UUID doctorB = newDoctor();
        UUID patient = newPatient();
        Callable<Void> autonomous = bookAutonomous(doctorB, patient, NEURAL, DAY, LocalTime.of(10, 0));

        Race race = race(bookManual(doctorA, patient, NEURAL, DAY, LocalTime.of(9, 0)), autonomous, ADVISORY);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(PatientAlreadyScheduledInSpecialtyException.class);
        assertSameAsSequential(race.competitor(), autonomous);
        assertThat(appointmentJpaRepository.findByIdPatient(patient)).hasSize(1);
    }

    @Test
    void autonomousBookingWinningFirst_doesNotBlockTheManualBookingOfTheSameSpecialty() throws Exception {
        UUID doctorA = newDoctor();
        UUID doctorB = newDoctor();
        UUID patient = newPatient();

        // La reserva manual está exenta de las restricciones de especialidad/cupo mensual.
        Race race = race(bookAutonomous(doctorA, patient, NEURAL, DAY, LocalTime.of(9, 0)),
                bookManual(doctorB, patient, NEURAL, DAY, LocalTime.of(10, 0)), ADVISORY);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isNull();
        assertThat(appointmentJpaRepository.findByIdPatient(patient)).hasSize(2);
        assertCommittedEventsMatch(doctorA, DAY);
        assertCommittedEventsMatch(doctorB, DAY);
    }

    // =================================================================== transiciones de estado

    private UUID scheduledAppointment(UUID doctor, UUID patient) {
        return seedAppointment(doctor, patient, NEURAL, AppointmentState.AGENDADA, DAY, LocalTime.of(9, 0),
                SchedulingOrigin.MANUAL);
    }

    @Test
    void attendanceHoldingTheRow_makesTheConcurrentCancellationLoseWithTheExistingConflict() throws Exception {
        UUID doctor = newDoctor();
        UUID patient = newPatient();
        UUID id = scheduledAppointment(doctor, patient);

        Race race = race(() -> {
            statusUseCase.markAsAttended(id, "Control sintético");
            return null;
        }, () -> {
            cancelUseCase.cancel(id, null);
            return null;
        }, ROW_LOCK);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(AppointmentStateConflictException.class)
                .hasMessage("Solo las citas AGENDADAS pueden cancelarse. Estado actual: ATENDIDA");
        assertThat(stateOf(id)).isEqualTo(AppointmentState.ATENDIDA);
        assertThat(clinicalHistoryRows(id)).isEqualTo(1);
    }

    @Test
    void cancellationHoldingTheRow_makesTheConcurrentAttendanceLoseWithoutClinicalHistory() throws Exception {
        UUID doctor = newDoctor();
        UUID patient = newPatient();
        UUID id = scheduledAppointment(doctor, patient);

        Race race = race(() -> {
            cancelUseCase.cancel(id, null);
            return null;
        }, () -> {
            statusUseCase.markAsAttended(id, "Control sintético");
            return null;
        }, ROW_LOCK);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(AppointmentStateConflictException.class)
                .hasMessage("Solo se puede cambiar el estado de citas agendadas");
        assertThat(stateOf(id)).isEqualTo(AppointmentState.CANCELADA);
        assertThat(clinicalHistoryRows(id)).as("la atención perdedora no debe dejar historia clínica").isZero();
    }

    @Test
    void unassistedHoldingTheRow_makesTheConcurrentCancellationLose() throws Exception {
        UUID id = scheduledAppointment(newDoctor(), newPatient());

        Race race = race(() -> {
            statusUseCase.markAsUnassisted(id);
            return null;
        }, () -> {
            cancelUseCase.cancel(id, null);
            return null;
        }, ROW_LOCK);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(AppointmentStateConflictException.class)
                .hasMessage("Solo las citas AGENDADAS pueden cancelarse. Estado actual: NO_ASISTIO");
        assertThat(stateOf(id)).isEqualTo(AppointmentState.NO_ASISTIO);
    }

    @Test
    void cancellationHoldingTheRow_makesTheConcurrentUnassistedLose() throws Exception {
        UUID id = scheduledAppointment(newDoctor(), newPatient());

        Race race = race(() -> {
            cancelUseCase.cancel(id, null);
            return null;
        }, () -> {
            statusUseCase.markAsUnassisted(id);
            return null;
        }, ROW_LOCK);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(AppointmentStateConflictException.class)
                .hasMessage("Solo se puede cambiar el estado de citas agendadas");
        assertThat(stateOf(id)).isEqualTo(AppointmentState.CANCELADA);
    }

    @Test
    void attendanceAndUnassisted_onlyOneCommitsAndNoClinicalHistoryFromTheLoser() throws Exception {
        UUID id = scheduledAppointment(newDoctor(), newPatient());

        Race race = race(() -> {
            statusUseCase.markAsUnassisted(id);
            return null;
        }, () -> {
            statusUseCase.markAsAttended(id, "Control sintético");
            return null;
        }, ROW_LOCK);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isInstanceOf(AppointmentStateConflictException.class);
        assertThat(stateOf(id)).isEqualTo(AppointmentState.NO_ASISTIO);
        assertThat(clinicalHistoryRows(id)).isZero();
    }

    @Test
    void theConflictKeepsItsExistingHttpContract() throws Exception {
        assertThat(new AppointmentStateConflictException("x").getStatus().value()).isEqualTo(500);
        assertThat(new AppointmentStateConflictException("x").getErrorCode()).isEqualTo("INTERNAL_ERROR");

        UUID id = scheduledAppointment(newDoctor(), newPatient());
        statusUseCase.markAsUnassisted(id);
        mockMvc.perform(put("/api/appointments/{id}/cancel", id).with(jwtWithRole("SCHEDULER")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail")
                        .value("Solo las citas AGENDADAS pueden cancelarse. Estado actual: NO_ASISTIO"));
    }

    @Test
    void cancellationThenRebookingWhileAStaleAttendanceIsPending_theStaleOperationCannotTouchTheRebookedSlot()
            throws Exception {
        UUID doctor = newDoctor();
        UUID patient = newPatient();
        UUID otherPatient = newPatient();
        UUID id = scheduledAppointment(doctor, patient);

        // La cancelación entra en su sección crítica; la atención (que cargó la cita "AGENDADA") queda esperando.
        Pause pause = new Pause(Phase.BEFORE_SAVE);
        Future<?> cancel = runPaused(pause, () -> {
            cancelUseCase.cancel(id, null);
            return null;
        });
        pause.awaitReached();
        Future<?> staleAttendance = run(() -> {
            statusUseCase.markAsAttended(id, "Control sintético");
            return null;
        });
        awaitBlockedOnLock(staleAttendance, ROW_LOCK);

        pause.release();
        assertThat(outcome(cancel)).isNull();
        // El hueco queda libre y se vuelve a reservar mientras la atención "pendiente" se resuelve.
        assertThat(outcome(run(bookManual(doctor, otherPatient, NEURAL, DAY, LocalTime.of(9, 0))))).isNull();
        assertThat(outcome(staleAttendance)).isInstanceOf(AppointmentStateConflictException.class);

        assertThat(stateOf(id)).isEqualTo(AppointmentState.CANCELADA);
        assertThat(clinicalHistoryRows(id)).isZero();
        List<AppointmentEntity> active = appointmentsOf(doctor, DAY).stream()
                .filter(a -> a.getAppointmentState() == AppointmentState.AGENDADA).toList();
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().getIdPatient()).isEqualTo(otherPatient);
    }

    @Test
    void attendanceHoldingTheRow_theSlotCannotBeRebookedAndTheLateCancellationLoses() throws Exception {
        UUID doctor = newDoctor();
        UUID patient = newPatient();
        UUID id = scheduledAppointment(doctor, patient);

        Pause pause = new Pause(Phase.BEFORE_SAVE);
        Future<?> attendance = runPaused(pause, () -> {
            statusUseCase.markAsAttended(id, "Control sintético");
            return null;
        });
        pause.awaitReached();
        Future<?> cancel = run(() -> {
            cancelUseCase.cancel(id, null);
            return null;
        });
        awaitBlockedOnLock(cancel, ROW_LOCK);
        pause.release();

        assertThat(outcome(attendance)).isNull();
        assertThat(outcome(cancel)).isInstanceOf(AppointmentStateConflictException.class);
        // ATENDIDA sigue ocupando el hueco: reservarlo de nuevo se rechaza como en secuencia.
        assertThat(outcome(run(bookManual(doctor, newPatient(), NEURAL, DAY, LocalTime.of(9, 0)))))
                .isInstanceOf(SlotNotAvailableException.class);
        assertThat(stateOf(id)).isEqualTo(AppointmentState.ATENDIDA);
    }

    // =================================================================== cierre nocturno (NO_ASISTIO)

    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    private UUID expiredAppointment(UUID doctor, UUID patient) {
        return seedAppointment(doctor, patient, NEURAL, AppointmentState.AGENDADA, YESTERDAY, LocalTime.of(9, 0),
                SchedulingOrigin.MANUAL);
    }

    @Test
    void nightlyJob_marksEveryExpiredScheduledAppointmentAndLeavesTheRestUntouched() {
        UUID doctor = newDoctor();
        UUID patient = newPatient();
        UUID a = expiredAppointment(doctor, patient);
        UUID b = seedAppointment(doctor, patient, NEURAL, AppointmentState.AGENDADA, YESTERDAY.minusDays(3),
                LocalTime.of(10, 0), SchedulingOrigin.MANUAL);
        UUID attended = seedAppointment(doctor, patient, NEURAL, AppointmentState.ATENDIDA, YESTERDAY,
                LocalTime.of(11, 0), SchedulingOrigin.MANUAL);
        UUID future = scheduledAppointment(doctor, patient);

        expiredUseCase.updateExpiredAppointments();

        assertThat(stateOf(a)).isEqualTo(AppointmentState.NO_ASISTIO);
        assertThat(stateOf(b)).isEqualTo(AppointmentState.NO_ASISTIO);
        assertThat(stateOf(attended)).isEqualTo(AppointmentState.ATENDIDA);
        assertThat(stateOf(future)).isEqualTo(AppointmentState.AGENDADA);
    }

    @Test
    void nightlyJobCompetingWithAttendanceHoldingTheRow_waitsAndDoesNotOverwriteIt() throws Exception {
        UUID id = expiredAppointment(newDoctor(), newPatient());

        Race race = race(() -> {
            statusUseCase.markAsAttended(id, "Control sintético");
            return null;
        }, () -> {
            expiredUseCase.updateExpiredAppointments();
            return null;
        }, ROW_LOCK);

        assertThat(race.holder()).isNull();
        assertThat(race.competitor()).isNull(); // 0 filas actualizadas: se omite sin error
        assertThat(stateOf(id)).isEqualTo(AppointmentState.ATENDIDA);
        assertThat(clinicalHistoryRows(id)).isEqualTo(1);
    }

    @Test
    void nightlyJobStaleWindow_attendanceCommittedAfterTheCandidateSelectionIsNotOverwritten() throws Exception {
        UUID id = expiredAppointment(newDoctor(), newPatient());

        Pause pause = new Pause(Phase.AFTER_LOAD);
        Future<?> job = runPaused(pause, () -> {
            expiredUseCase.updateExpiredAppointments();
            return null;
        });
        pause.awaitReached(); // candidatos ya seleccionados (la cita figura AGENDADA), nada modificado aún
        statusUseCase.markAsAttended(id, "Control sintético"); // transición confirmada en la ventana
        pause.release();

        assertThat(outcome(job)).isNull();
        assertThat(stateOf(id)).as("el cierre nocturno no debe sobrescribir ATENDIDA").isEqualTo(AppointmentState.ATENDIDA);
        assertThat(clinicalHistoryRows(id)).isEqualTo(1);
    }

    @Test
    void nightlyJobStaleWindow_unassistedAndCancelledStatesAreNotOverwritten() throws Exception {
        UUID doctor = newDoctor();
        UUID patient = newPatient();
        UUID unassisted = expiredAppointment(doctor, patient);
        UUID cancelled = seedAppointment(doctor, patient, NEURAL, AppointmentState.AGENDADA, YESTERDAY,
                LocalTime.of(10, 0), SchedulingOrigin.MANUAL);

        Pause pause = new Pause(Phase.AFTER_LOAD);
        Future<?> job = runPaused(pause, () -> {
            expiredUseCase.updateExpiredAppointments();
            return null;
        });
        pause.awaitReached();
        statusUseCase.markAsUnassisted(unassisted);
        // Una cita de fecha pasada ya no puede cancelarse por el caso de uso; se simula la cancelación
        // confirmada justo antes de medianoche con una escritura directa.
        jdbc.update("update piedrazul.appointment set state_code = 'CANCELADA' where id = ?", cancelled);
        pause.release();

        assertThat(outcome(job)).isNull();
        assertThat(stateOf(unassisted)).isEqualTo(AppointmentState.NO_ASISTIO);
        assertThat(stateOf(cancelled)).isEqualTo(AppointmentState.CANCELADA);
    }

    // =================================================================== orden de bloqueo / sin deadlock

    @Test
    void crossedDoctorPatientCombinations_neverDeadlock() throws Exception {
        UUID d1 = newDoctor();
        UUID d2 = newDoctor();
        UUID p1 = newPatient();
        UUID p2 = newPatient();
        UUID[] doctors = {d1, d2};
        UUID[] patients = {p1, p2};

        for (int round = 0; round < 5; round++) {
            LocalDate date = LocalDate.now().plusDays(10 + round);
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                UUID doctor = doctors[i % 2];
                UUID patient = patients[(i / 2) % 2];
                LocalTime time = LocalTime.of(6, 0).plusMinutes(30L * i); // horas distintas: todas son válidas
                Callable<Void> booking = bookManual(doctor, patient, NEURAL, date, time);
                futures.add(run(() -> {
                    gate.await();
                    return booking.call();
                }));
            }
            gate.countDown();
            for (Future<?> future : futures) {
                assertThat(outcome(future)).as("ninguna reserva válida debe fallar (ni por deadlock)").isNull();
            }
            assertThat(appointmentsOf(d1, date)).hasSize(4);
            assertThat(appointmentsOf(d2, date)).hasSize(4);
        }
    }

    // =================================================================== compatibilidad secuencial

    @Test
    void missingAppointment_keepsTheExactMessageAndTheHttp400() throws Exception {
        UUID missing = UUID.randomUUID();
        String message = "Cita con ID: " + missing + "no encontrada";

        assertThatThrownBy(() -> cancelUseCase.cancel(missing, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> statusUseCase.markAsAttended(missing, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThatThrownBy(() -> statusUseCase.markAsUnassisted(missing))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message);

        mockMvc.perform(put("/api/appointments/{id}/cancel", missing).with(jwtWithRole("SCHEDULER")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(message));
        mockMvc.perform(put("/api/appointments/{id}/mark-as-attended", missing).with(jwtWithRole("DOCTOR")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(message));
        mockMvc.perform(put("/api/appointments/{id}/mark-as-unassisted", missing).with(jwtWithRole("DOCTOR")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(message));
    }

    @Test
    void cancellationAndRebooking_remainPossibleSequentially() throws Exception {
        UUID doctor = newDoctor();
        UUID id = scheduledAppointment(doctor, newPatient());

        cancelUseCase.cancel(id, null);
        bookManual(doctor, newPatient(), NEURAL, DAY, LocalTime.of(9, 0)).call();

        assertThat(stateOf(id)).isEqualTo(AppointmentState.CANCELADA);
        assertThat(appointmentsOf(doctor, DAY)).filteredOn(a -> a.getAppointmentState() == AppointmentState.AGENDADA)
                .hasSize(1);
        // La cita sembrada no publica eventos: solo la reserva nueva debe haberlos publicado.
        assertThat(committedEvents.notifications()).filteredOn(e -> doctor.equals(e.doctorId())).hasSize(1);
    }

    @Test
    void sequentialSlotAndPatientConflicts_keepTheirDomainErrors() throws Exception {
        UUID doctor = newDoctor();
        UUID otherDoctor = newDoctor();
        UUID patient = newPatient();
        bookManual(doctor, patient, NEURAL, DAY, LocalTime.of(9, 0)).call();

        assertThatThrownBy(() -> bookManual(doctor, newPatient(), NEURAL, DAY, LocalTime.of(9, 15)).call())
                .isInstanceOf(SlotNotAvailableException.class);
        assertThatThrownBy(() -> bookManual(otherDoctor, patient, NEURAL, DAY, LocalTime.of(9, 0)).call())
                .isInstanceOf(PatientScheduleTimeConflictException.class);
        bookManual(doctor, newPatient(), NEURAL, DAY, LocalTime.of(9, 30)).call(); // adyacente: permitido
        assertThat(appointmentsOf(doctor, DAY)).hasSize(2);
    }

    // ------------------------------------------------------------------ utilidades HTTP

    private JwtRequestPostProcessor jwtWithRole(String role) {
        return jwt()
                .jwt(builder -> builder.subject(UUID.randomUUID().toString())
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
