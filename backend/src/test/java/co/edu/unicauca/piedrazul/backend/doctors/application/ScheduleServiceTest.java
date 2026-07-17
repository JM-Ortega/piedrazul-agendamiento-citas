package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.ScheduleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @InjectMocks
    private ScheduleService scheduleService;

    @Test
    void addScheduleShouldPersistScheduleWhenWorkdayIsFree() {
        Doctor doctor = new Doctor(UUID.fromString("66666666-6666-6666-6666-666666666666"), LocalDate.now().minusDays(5), LocalDate.now().plusDays(5), 4, true, 20);
        Schedule schedule = new Schedule(null, LocalTime.of(8, 0), LocalTime.of(12, 0), Workday.LUNES);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of());
        when(scheduleRepository.save(org.mockito.ArgumentMatchers.any(Schedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Schedule result = scheduleService.addSchedule(doctor, schedule);

        ArgumentCaptor<Schedule> scheduleCaptor = ArgumentCaptor.forClass(Schedule.class);
        verify(scheduleRepository).save(scheduleCaptor.capture());

        assertThat(result).isSameAs(schedule);
        assertThat(scheduleCaptor.getValue().getDoctor()).isSameAs(doctor);
        assertThat(schedule.getDoctor()).isSameAs(doctor);
    }

    @Test
    void addScheduleShouldRejectDuplicatedWorkday() {
        Doctor doctor = new Doctor(UUID.fromString("77777777-7777-7777-7777-777777777777"), LocalDate.now().minusDays(5), LocalDate.now().plusDays(5), 4, true, 20);
        Schedule existing = new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(12, 0), Workday.LUNES);
        Schedule incoming = new Schedule(null, LocalTime.of(13, 0), LocalTime.of(15, 0), Workday.LUNES);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of(existing));

        assertThrows(DoctorScheduleConflictException.class, () -> scheduleService.addSchedule(doctor, incoming));

        verify(scheduleRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateScheduleByWorkdayShouldUpdateExistingSchedule() {
        Doctor doctor = new Doctor(UUID.fromString("88888888-8888-8888-8888-888888888888"), LocalDate.now().minusDays(5), LocalDate.now().plusDays(5), 4, true, 20);
        Schedule existing = new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(12, 0), Workday.MARTES);
        Schedule replacement = new Schedule(null, LocalTime.of(9, 0), LocalTime.of(11, 0), Workday.MARTES);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of(existing));
        when(scheduleRepository.save(org.mockito.ArgumentMatchers.any(Schedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Schedule result = scheduleService.updateScheduleByWorkday(doctor, Workday.MARTES, replacement);

        assertThat(result.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(result.getEndTime()).isEqualTo(LocalTime.of(11, 0));
        verify(scheduleRepository).save(existing);
    }

    @Test
    void getAvailableIntervalsByWorkdayShouldReturnCompleteSlotList() {
        Doctor doctor = new Doctor(UUID.fromString("99999999-9999-9999-9999-999999999999"), LocalDate.now().minusDays(5), LocalDate.now().plusDays(5), 4, true, 20);
        Schedule schedule = new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(9, 0), Workday.MIERCOLES);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of(schedule));

        List<LocalTime> result = scheduleService.getAvailableIntervalsByWorkday(doctor, Workday.MIERCOLES);

        assertThat(result).containsExactly(
                LocalTime.of(8, 0),
                LocalTime.of(8, 20),
                LocalTime.of(8, 40)
        );
    }

    @Test
    void getAvailableIntervalsByWorkdayShouldFailWhenDoctorHasNoScheduleForThatDay() {
        Doctor doctor = new Doctor(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), LocalDate.now().minusDays(5), LocalDate.now().plusDays(5), 4, true, 20);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of());

        assertThrows(DoctorScheduleNotFoundException.class, () -> scheduleService.getAvailableIntervalsByWorkday(doctor, Workday.JUEVES));
    }
}