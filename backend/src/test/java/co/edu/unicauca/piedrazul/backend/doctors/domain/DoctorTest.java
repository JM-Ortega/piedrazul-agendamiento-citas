package co.edu.unicauca.piedrazul.backend.doctors.domain;

import co.edu.unicauca.piedrazul.backend.doctors.exception.DateConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorValidationException;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;

import co.edu.unicauca.piedrazul.backend.shared.enums.Workday;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pruebas unitarias para la entidad de dominio Doctor.
 *
 * Supuestos (AJUSTAR si no coinciden con tu código real):
 * - Schedule tiene constructor Schedule(Doctor doctor, LocalTime start, LocalTime end, Workday workday)
 *   y métodos getWorkday(), getStartTime(), getEndTime(), updateHours(start, end).
 * - Specialty tiene constructor accesible o builder simple con getCode().
 * - Workday es un enum con al menos LUNES y MARTES (ajusta a tus valores reales, ej. MONDAY/TUESDAY).
 */
class DoctorTest {

    private UUID personId;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        personId = UUID.randomUUID();
        today = LocalDate.now();
    }

    private Doctor buildDoctor(LocalDate start, LocalDate end, int weeks, int interval) {
        return new Doctor(personId, start, end, weeks, false, interval);
    }

    /**
     * Specialty no expone constructor público ni setters, solo el @Id (code) y name,
     * con equals/hashCode basados únicamente en code. Se arma vía reflexión (requiere
     * que Specialty tenga constructor sin argumentos, típico de una entidad JPA).
     */
    private Specialty buildSpecialty(SpecialtyCode code) {
        Specialty specialty = new Specialty();
        ReflectionTestUtils.setField(specialty, "code", code);
        ReflectionTestUtils.setField(specialty, "name", code.name());
        return specialty;
    }

    @Nested
    class ConstructorTests {

        @Test
        void shouldCreateDoctorWithGivenFields() {
            Doctor doctor = new Doctor(personId, today, today.plusMonths(6), 4, true, 30);

            assertThat(doctor.getPersonId()).isEqualTo(personId);
            assertThat(doctor.getLaborStart()).isEqualTo(today);
            assertThat(doctor.getLaborEnd()).isEqualTo(today.plusMonths(6));
            assertThat(doctor.getBookingWindowWeeks()).isEqualTo(4);
            assertThat(doctor.isStatus()).isTrue();
            assertThat(doctor.getAppointmentInterval()).isEqualTo(30);
            assertThat(doctor.getSchedules()).isEmpty();
            assertThat(doctor.getSpecialties()).isEmpty();
        }
    }

    @Nested
    class ScheduleTests {

        @Test
        void shouldAddNewScheduleWhenNoneExistsForWorkday() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(12, 0));

            assertThat(doctor.getSchedules()).hasSize(1);
            Schedule schedule = doctor.getSchedules().iterator().next();
            assertThat(schedule.getWorkday()).isEqualTo(Workday.LUNES);
            assertThat(schedule.getStartTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(schedule.getEndTime()).isEqualTo(LocalTime.of(12, 0));
        }

        @Test
        void shouldThrowWhenEndTimeIsNotAfterStartTimeOnNewSchedule() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            assertThatThrownBy(() ->
                    doctor.updateSchedule(Workday.LUNES, LocalTime.of(12, 0), LocalTime.of(12, 0))
            ).isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldUpdateHoursWhenScheduleAlreadyExistsForWorkday() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(12, 0));

            doctor.updateSchedule(Workday.LUNES, LocalTime.of(9, 0), LocalTime.of(13, 0));

            assertThat(doctor.getSchedules()).hasSize(1);
            Schedule schedule = doctor.getSchedules().iterator().next();
            assertThat(schedule.getStartTime()).isEqualTo(LocalTime.of(9, 0));
            assertThat(schedule.getEndTime()).isEqualTo(LocalTime.of(13, 0));
        }

        @Test
        void shouldRemoveScheduleByWorkday() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(12, 0));
            doctor.updateSchedule(Workday.MARTES, LocalTime.of(8, 0), LocalTime.of(12, 0));

            doctor.removeSchedule(Workday.LUNES);

            assertThat(doctor.getSchedules()).hasSize(1);
            assertThat(doctor.getSchedules().iterator().next().getWorkday()).isEqualTo(Workday.MARTES);
        }
    }

    @Nested
    class UpdateInfoTests {

        @Test
        void shouldUpdateAllFieldsWhenValid() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            LocalDate newStart = today.plusDays(1);
            LocalDate newEnd = today.plusMonths(6);

            doctor.updateInfo(newStart, newEnd, 6, 45);

            assertThat(doctor.getLaborStart()).isEqualTo(newStart);
            assertThat(doctor.getLaborEnd()).isEqualTo(newEnd);
            assertThat(doctor.getBookingWindowWeeks()).isEqualTo(6);
            assertThat(doctor.getAppointmentInterval()).isEqualTo(45);
        }

        @Test
        void shouldThrowWhenLaborStartIsNull() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            assertThatThrownBy(() -> doctor.updateInfo(null, today.plusMonths(3), 4, 30))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldThrowWhenLaborEndIsNull() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            assertThatThrownBy(() -> doctor.updateInfo(today, null, 4, 30))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldThrowWhenLaborEndBeforeLaborStart() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            assertThatThrownBy(() -> doctor.updateInfo(today, today.minusDays(1), 4, 30))
                    .isInstanceOf(DateConflictException.class);
        }

        @Test
        void shouldThrowWhenWeeksIsZeroOrNegative() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            assertThatThrownBy(() -> doctor.updateInfo(today, today.plusMonths(3), 0, 30))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldThrowWhenMinutesIsZeroOrNegative() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            assertThatThrownBy(() -> doctor.updateInfo(today, today.plusMonths(3), 4, 0))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldThrowWhenNewIntervalExceedsAllExistingScheduleDurations() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            // horario de 1 hora (60 min)
            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(9, 0));

            assertThatThrownBy(() -> doctor.updateInfo(today, today.plusMonths(3), 4, 90))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldAllowIntervalThatFitsAtLeastOneExistingSchedule() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(9, 0)); // 60 min

            doctor.updateInfo(today, today.plusMonths(3), 45, 4);

            assertThat(doctor.getAppointmentInterval()).isEqualTo(45);
        }
    }

    @Nested
    class UpdateLaborPeriodTests {

        @Test
        void shouldUpdatePeriodWhenValid() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            doctor.updateLaborPeriod(today, today.plusMonths(1));

            assertThat(doctor.getLaborEnd()).isEqualTo(today.plusMonths(1));
        }

        @Test
        void shouldThrowWhenStartIsNull() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            assertThatThrownBy(() -> doctor.updateLaborPeriod(null, today))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldThrowWhenEndBeforeStart() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            assertThatThrownBy(() -> doctor.updateLaborPeriod(today, today.minusDays(1)))
                    .isInstanceOf(DateConflictException.class);
        }
    }

    @Nested
    class BookingWindowAndIntervalTests {

        @Test
        void shouldUpdateBookingWindowWhenPositive() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            doctor.updateBookingWindow(8);

            assertThat(doctor.getBookingWindowWeeks()).isEqualTo(8);
        }

        @Test
        void shouldThrowWhenBookingWindowIsZeroOrNegative() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            assertThatThrownBy(() -> doctor.updateBookingWindow(0))
                    .isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldUpdateAppointmentIntervalWhenValid() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);

            doctor.updateAppointmentInterval(20);

            assertThat(doctor.getAppointmentInterval()).isEqualTo(20);
        }

        @Test
        void shouldThrowWhenIntervalTooLargeForExistingSchedules() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(9, 0)); // 60 min

            assertThatThrownBy(() -> doctor.updateAppointmentInterval(120))
                    .isInstanceOf(DoctorValidationException.class);
        }
    }

    @Nested
    class SpecialtyTests {

        @Test
        void shouldAddSpecialty() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            Specialty specialty = buildSpecialty(SpecialtyCode.TERAPIA_NEURAL); // AJUSTAR constructor real

            doctor.addSpecialty(specialty);

            assertThat(doctor.getSpecialties()).contains(specialty);
        }

        @Test
        void shouldReplaceSpecialtiesEntirely() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            Specialty oldOne = buildSpecialty(SpecialtyCode.TERAPIA_NEURAL);
            doctor.addSpecialty(oldOne);

            Set<Specialty> newSpecialties = new HashSet<>(Set.of(buildSpecialty(SpecialtyCode.FISIOTERAPIA)));
            doctor.replaceSpecialties(newSpecialties);

            assertThat(doctor.getSpecialties()).isEqualTo(newSpecialties);
        }

        @Test
        void shouldReportHasSpecialtyCorrectly() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            doctor.addSpecialty(buildSpecialty(SpecialtyCode.TERAPIA_NEURAL));

            assertThat(doctor.hasSpecialtie(SpecialtyCode.TERAPIA_NEURAL)).isTrue();
            assertThat(doctor.hasSpecialtie(SpecialtyCode.FISIOTERAPIA)).isFalse();
        }
    }

    @Nested
    class ActivationTests {

        private Doctor validDoctorReadyToActivate() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(12, 0));
            doctor.addSpecialty(buildSpecialty(SpecialtyCode.TERAPIA_NEURAL));
            return doctor;
        }

        @Test
        void shouldActivateWhenAllConditionsMet() {
            Doctor doctor = validDoctorReadyToActivate();

            doctor.activate();

            assertThat(doctor.isStatus()).isTrue();
        }

        @Test
        void shouldNotActivateWhenLaborEndInPast() {
            Doctor doctor = buildDoctor(today.minusMonths(2), today.minusDays(1), 4, 30);
            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(12, 0));
            doctor.addSpecialty(buildSpecialty(SpecialtyCode.TERAPIA_NEURAL));

            assertThatThrownBy(doctor::activate).isInstanceOf(DateConflictException.class);
        }

        @Test
        void shouldNotActivateWithoutSchedules() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            doctor.addSpecialty(buildSpecialty(SpecialtyCode.TERAPIA_NEURAL));

            assertThatThrownBy(doctor::activate).isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldNotActivateWithoutSpecialties() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30);
            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(12, 0));

            assertThatThrownBy(doctor::activate).isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void shouldNotActivateWithInvalidBookingWindow() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 0, 30);
            doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(12, 0));
            doctor.addSpecialty(buildSpecialty(SpecialtyCode.TERAPIA_NEURAL));

            assertThatThrownBy(doctor::activate).isInstanceOf(DoctorValidationException.class);
        }

        @Test
        void canBeActivatedShouldReturnFalseInsteadOfThrowing() {
            Doctor doctor = buildDoctor(today, today.plusMonths(3), 4, 30); // sin horarios/especialidades

            assertThat(doctor.canBeActivated()).isFalse();
        }

        @Test
        void canBeActivatedShouldReturnTrueWhenValid() {
            Doctor doctor = validDoctorReadyToActivate();

            assertThat(doctor.canBeActivated()).isTrue();
        }

        @Test
        void activateIfPossibleShouldSetStatusAccordingly() {
            Doctor incomplete = buildDoctor(today, today.plusMonths(3), 4, 30);
            incomplete.activateIfPossible();
            assertThat(incomplete.isStatus()).isFalse();

            Doctor complete = validDoctorReadyToActivate();
            complete.activateIfPossible();
            assertThat(complete.isStatus()).isTrue();
        }

        @Test
        void deactivateShouldSetStatusFalse() {
            Doctor doctor = validDoctorReadyToActivate();
            doctor.activate();

            doctor.deactivate();

            assertThat(doctor.isStatus()).isFalse();
        }
    }

    @Nested
    class EqualsAndHashCodeTests {

        @Test
        void shouldBeEqualWhenSamePersonId() {
            Doctor a = buildDoctor(today, today.plusMonths(3), 4, 30);
            Doctor b = new Doctor(personId, today.plusDays(10), today.plusMonths(5), 8, true, 45);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void shouldNotBeEqualWhenDifferentPersonId() {
            Doctor a = buildDoctor(today, today.plusMonths(3), 4, 30);
            Doctor b = buildDoctor(today, today.plusMonths(3), 4, 30); // distinto personId (nuevo random)

            // Nota: buildDoctor reutiliza el mismo personId del setUp, así que forzamos otro aquí
            Doctor c = new Doctor(UUID.randomUUID(), today, today.plusMonths(3), 4, false, 30);

            assertThat(a).isNotEqualTo(c);
        }
    }
}