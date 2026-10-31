package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.DoctorTimeOff;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorTimeOffRepository;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import de.focus_shift.jollyday.core.HolidayManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorExternalServiceImplTest {

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private PersonExternalService personExternalService;

    @Mock
    private HolidayManager holidayManager;

    @Mock
    private DoctorTimeOffRepository timeOffRepository;

    @Test
    void workingScheduleShouldReturnEmptyWhenBookingWindowHasExpired() {
        UUID doctorId = UUID.randomUUID();
        Doctor doctor = mock(Doctor.class);
        LocalDate today = LocalDate.now();

        when(doctorRepository.findByPersonId(doctorId)).thenReturn(doctor);
        when(doctor.getLaborStart()).thenReturn(today.minusWeeks(27));
        when(doctor.getLaborEnd()).thenReturn(today.plusMonths(1));
        when(doctor.getBookingWindowWeeks()).thenReturn(26);
        when(doctor.getAppointmentInterval()).thenReturn(30);
        when(doctor.getSchedules()).thenReturn(java.util.Set.of());

        DoctorExternalServiceImpl service = new DoctorExternalServiceImpl(
                doctorRepository,
                scheduleService,
                personExternalService,
                holidayManager,
                timeOffRepository
        );

        assertThat(service.workingSchedule(doctorId).datesAndSlots()).isEqualTo(List.of());
        verifyNoInteractions(holidayManager);
    }

    @Test
    void workingScheduleShouldExcludeTimeOffDatesAndRestoreThemOnceRemoved() {
        UUID doctorId = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        Doctor doctor = new Doctor(doctorId, today.minusMonths(1), today.plusMonths(6), 4, true, 30);
        for (co.edu.unicauca.piedrazul.backend.shared.enums.Workday d
                : co.edu.unicauca.piedrazul.backend.shared.enums.Workday.values()) {
            doctor.updateSchedule(d, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(10, 0));
        }
        when(doctorRepository.findByPersonId(doctorId)).thenReturn(doctor);
        when(holidayManager.isHoliday(any(LocalDate.class))).thenReturn(false);

        DoctorExternalServiceImpl service = new DoctorExternalServiceImpl(
                doctorRepository, scheduleService, personExternalService, holidayManager, timeOffRepository);

        List<LocalDate> before = service.workingSchedule(doctorId).datesAndSlots().stream()
                .map(co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingDateSlots::date).toList();
        assertThat(before).isNotEmpty();

        // Descanso que cubre del 2.º al 3.er día hábil disponible, extremos incluidos
        LocalDate from = before.get(1);
        LocalDate to = before.get(2);
        when(timeOffRepository.findByDoctorIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                eq(doctorId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new DoctorTimeOff(doctorId, from, to, null)));

        List<LocalDate> during = service.workingSchedule(doctorId).datesAndSlots().stream()
                .map(co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingDateSlots::date).toList();

        assertThat(during).doesNotContain(from, to).contains(before.getFirst());
        assertThat(during).hasSize(before.size() - 2);

        // Al eliminar el descanso las fechas vuelven
        when(timeOffRepository.findByDoctorIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                eq(doctorId), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
        List<LocalDate> after = service.workingSchedule(doctorId).datesAndSlots().stream()
                .map(co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingDateSlots::date).toList();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void isOnTimeOffShouldDelegateToRepositoryUsingSameDateAsBothBounds() {
        UUID doctorId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(40);
        when(timeOffRepository.existsByDoctorIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(doctorId, date, date))
                .thenReturn(true);

        DoctorExternalServiceImpl service = new DoctorExternalServiceImpl(
                doctorRepository, scheduleService, personExternalService, holidayManager, timeOffRepository);

        assertThat(service.isOnTimeOff(doctorId, date)).isTrue();
    }
}
