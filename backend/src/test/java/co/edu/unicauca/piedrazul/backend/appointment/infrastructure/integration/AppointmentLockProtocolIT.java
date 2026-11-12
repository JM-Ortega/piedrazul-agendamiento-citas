package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Protocolo de bloqueo contra el cableado real (AppointmentConfig + transacciones de producción):
 * los locks advisory exigen y siguen a una transacción, se liberan al terminar (commit o rollback),
 * están separados por espacio de nombres y se toman en el orden doctor -> paciente.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class AppointmentLockProtocolIT extends AppointmentConcurrencyITSupport {

    private static final LocalDate DAY = LocalDate.now().plusDays(4);

    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private long advisoryLocks(boolean granted) {
        Long count = jdbc.queryForObject(
                "select count(*) from pg_locks where locktype = 'advisory' and granted = ?", Long.class, granted);
        return count == null ? 0 : count;
    }

    private long advisoryLocksHeldByThisBackend() {
        // Mismo hilo y misma transacción => misma conexión que la de los locks
        Long count = jdbc.queryForObject(
                "select count(*) from pg_locks where locktype = 'advisory' and granted and pid = pg_backend_pid()",
                Long.class);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ cableado real

    @Test
    void productionBeansAreTransactionalProxies() {
        assertThat(AopUtils.isAopProxy(schedulingService)).as("AppointmentSchedulingService").isTrue();
        assertThat(AopUtils.isAopProxy(cancelUseCase)).as("CancelAppointmentUseCase").isTrue();
        assertThat(AopUtils.isAopProxy(statusUseCase)).as("UpdateAppointmentStatusUseCase").isTrue();
        assertThat(AopUtils.isAopProxy(appointmentRepository)).as("AppointmentRepository").isTrue();
        assertThat(schedulingService).isInstanceOf(AppointmentSchedulingService.class);
    }

    @Test
    void lockAndLockedLoadRequireAnActiveTransaction() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> appointmentRepository.lockDoctorSchedule(id))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> appointmentRepository.lockPatientSchedule(id))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> appointmentRepository.findByIdForUpdate(id))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void cancellationOpensItsOwnTransactionForTheLockedLoad() {
        UUID doctor = newDoctor();
        UUID id = seedAppointment(doctor, newPatient(), SpecialtyCode.TERAPIA_NEURAL, AppointmentState.AGENDADA, DAY,
                LocalTime.of(9, 0), SchedulingOrigin.MANUAL);

        // Sin transacción externa: si cancel() no fuera transaccional, findByIdForUpdate (MANDATORY) fallaría.
        cancelUseCase.cancel(id, null);

        assertThat(stateOf(id)).isEqualTo(AppointmentState.CANCELADA);
    }

    // ------------------------------------------------------------------ vida del lock

    @Test
    void advisoryLocksAreHeldUntilCommitAndReleasedAfterwards() {
        UUID doctor = UUID.randomUUID();
        UUID patient = UUID.randomUUID();
        long before = advisoryLocks(true);

        tx().executeWithoutResult(status -> {
            appointmentRepository.lockDoctorSchedule(doctor);
            assertThat(advisoryLocksHeldByThisBackend()).isEqualTo(1);
            appointmentRepository.lockPatientSchedule(patient);
            assertThat(advisoryLocksHeldByThisBackend()).isEqualTo(2);
            // Reentrante dentro de la misma transacción: no se bloquea consigo misma
            appointmentRepository.lockDoctorSchedule(doctor);
            assertThat(advisoryLocksHeldByThisBackend()).isEqualTo(2);
        });

        assertThat(advisoryLocks(true)).isEqualTo(before);
    }

    @Test
    void advisoryLocksAreReleasedOnRollback() {
        long before = advisoryLocks(true);

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            appointmentRepository.lockDoctorSchedule(UUID.randomUUID());
            appointmentRepository.lockPatientSchedule(UUID.randomUUID());
            throw new IllegalStateException("fallo simulado");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(advisoryLocks(true)).isEqualTo(before);
    }

    @Test
    void doctorAndPatientLocksUseSeparateNamespaces() throws Exception {
        UUID sameUuid = UUID.randomUUID();
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(1);

        Future<?> holder = run(() -> {
            tx().executeWithoutResult(status -> {
                appointmentRepository.lockDoctorSchedule(sameUuid);
                holding.countDown();
                await(finish);
            });
            return null;
        });
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        // Mismo UUID pero otro espacio de nombres: no espera
        Future<?> patientLock = run(() -> {
            tx().executeWithoutResult(status -> appointmentRepository.lockPatientSchedule(sameUuid));
            return null;
        });
        assertThat(outcome(patientLock)).isNull();

        // Misma clave: espera hasta que el titular termina
        Future<?> sameKey = run(() -> {
            tx().executeWithoutResult(status -> appointmentRepository.lockDoctorSchedule(sameUuid));
            return null;
        });
        awaitBlockedOnLock(sameKey, ADVISORY);
        finish.countDown();
        assertThat(outcome(holder)).isNull();
        assertThat(outcome(sameKey)).isNull();
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ orden doctor -> paciente

    @Test
    void competingBookingHoldsItsDoctorLockWhileWaitingForThePatientLock() throws Exception {
        UUID doctorA = newDoctor();
        UUID doctorB = newDoctor();
        UUID patient = newPatient();

        Pause pause = new Pause(Phase.BEFORE_SAVE);
        Future<?> holder = runPaused(pause, bookManual(doctorA, patient, SpecialtyCode.TERAPIA_NEURAL, DAY, LocalTime.of(9, 0)));
        pause.awaitReached();
        long heldAfterHolder = advisoryLocks(true);
        assertThat(heldAfterHolder).as("titular: lock del doctor y lock del paciente").isGreaterThanOrEqualTo(2);

        // Otro doctor, mismo paciente: pasa el lock del doctor B y queda esperando el del paciente
        Future<?> competitor = run(bookManual(doctorB, patient, SpecialtyCode.TERAPIA_NEURAL, DAY, LocalTime.of(10, 0)));
        awaitBlockedOnLock(competitor, ADVISORY);
        assertThat(advisoryLocks(false)).as("exactamente un lock esperado: el del paciente").isEqualTo(1);
        assertThat(advisoryLocks(true)).as("el competidor ya retiene el lock del doctor B")
                .isEqualTo(heldAfterHolder + 1);

        pause.release();
        assertThat(outcome(holder)).isNull();
        assertThat(outcome(competitor)).isNull(); // manual: sin restricción de especialidad
    }

    // ------------------------------------------------------------------ actualización condicional atómica

    @Test
    void conditionalUpdateOnlyChangesAScheduledAppointment() {
        UUID id = seedAppointment(newDoctor(), newPatient(), SpecialtyCode.TERAPIA_NEURAL, AppointmentState.AGENDADA,
                LocalDate.now().minusDays(1), LocalTime.of(9, 0), SchedulingOrigin.MANUAL);

        assertThat(appointmentRepository.markUnassistedIfScheduled(id)).isTrue();
        assertThat(stateOf(id)).isEqualTo(AppointmentState.NO_ASISTIO);
        assertThat(appointmentRepository.markUnassistedIfScheduled(id)).isFalse();

        UUID attended = seedAppointment(newDoctor(), newPatient(), SpecialtyCode.TERAPIA_NEURAL,
                AppointmentState.ATENDIDA, LocalDate.now().minusDays(1), LocalTime.of(9, 0), SchedulingOrigin.MANUAL);
        assertThat(appointmentRepository.markUnassistedIfScheduled(attended)).isFalse();
        assertThat(stateOf(attended)).isEqualTo(AppointmentState.ATENDIDA);
        assertThat(appointmentRepository.markUnassistedIfScheduled(UUID.randomUUID())).isFalse();
    }
}
