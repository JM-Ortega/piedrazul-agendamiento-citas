package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.ScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private DoctorRepository doctorRepository;

    @InjectMocks
    private ScheduleService scheduleService;

    @Test
    void updateScheduleShouldCreateScheduleWhenWorkdayDoesNotExist() {

        UUID doctorId = UUID.randomUUID();

        Doctor doctor = new Doctor(
                doctorId,
                LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(5),
                4,
                true,
                20
        );

        ScheduleRequest request = new ScheduleRequest(
                LocalTime.of(8, 0),
                LocalTime.of(12, 0),
                Workday.LUNES
        );

        when(doctorRepository.findById(doctorId))
                .thenReturn(Optional.of(doctor));

        scheduleService.updateSchedule(doctorId, request);

        assertThat(doctor.getSchedules())
                .hasSize(1);

        Schedule schedule = doctor.getSchedules().iterator().next();

        assertThat(schedule.getWorkday()).isEqualTo(Workday.LUNES);
        assertThat(schedule.getStartTime()).isEqualTo(LocalTime.of(8,0));
        assertThat(schedule.getEndTime()).isEqualTo(LocalTime.of(12,0));

        verify(doctorRepository).save(doctor);
    }

    @Test
    void updateScheduleShouldModifyExistingSchedule() {

        UUID doctorId = UUID.randomUUID();

        Doctor doctor = new Doctor(
                doctorId,
                LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(5),
                4,
                true,
                20
        );

        doctor.updateSchedule(
                Workday.MARTES,
                LocalTime.of(8,0),
                LocalTime.of(12,0)
        );

        ScheduleRequest request = new ScheduleRequest(
                LocalTime.of(9,0),
                LocalTime.of(11,0),
                Workday.MARTES
        );

        when(doctorRepository.findById(doctorId))
                .thenReturn(Optional.of(doctor));

        scheduleService.updateSchedule(doctorId, request);

        Schedule updated = doctor.getSchedules()
                .stream()
                .filter(s -> s.getWorkday() == Workday.MARTES)
                .findFirst()
                .orElseThrow();

        assertThat(updated.getStartTime()).isEqualTo(LocalTime.of(9,0));
        assertThat(updated.getEndTime()).isEqualTo(LocalTime.of(11,0));

        verify(doctorRepository).save(doctor);
    }

    @Test
    void deleteScheduleShouldRemoveSchedule() {

        Doctor doctor = new Doctor(
                UUID.randomUUID(),
                LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(5),
                4,
                true,
                20
        );

        doctor.updateSchedule(
                Workday.LUNES,
                LocalTime.of(8,0),
                LocalTime.of(12,0)
        );

        when(doctorRepository.findById(doctor.getPersonId()))
                .thenReturn(Optional.of(doctor));

        scheduleService.deleteScheduleByWorkday(
                doctor.getPersonId(),
                Workday.LUNES
        );

        assertThat(doctor.getSchedules()).isEmpty();

        verify(doctorRepository).save(doctor);
    }

    @Test
    void getSchedulesShouldReturnDoctorSchedules() {

        Doctor doctor = new Doctor(
                UUID.randomUUID(),
                LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(5),
                4,
                true,
                20
        );

        doctor.updateSchedule(
                Workday.LUNES,
                LocalTime.of(8,0),
                LocalTime.of(12,0)
        );

        when(doctorRepository.findById(doctor.getPersonId()))
                .thenReturn(Optional.of(doctor));

        List<Schedule> schedules =
                scheduleService.getSchedulesByDoctor(doctor.getPersonId());

        assertThat(schedules).hasSize(1);
    }

    @Test
    void getAvailableIntervalsByWorkdayShouldReturnCompleteSlotList() {

        Doctor doctor = new Doctor(
                UUID.randomUUID(),
                LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(5),
                4,
                true,
                20
        );

        doctor.updateSchedule(
                Workday.MIERCOLES,
                LocalTime.of(8,0),
                LocalTime.of(9,0)
        );

        when(doctorRepository.findById(doctor.getPersonId()))
                .thenReturn(Optional.of(doctor));

        List<LocalTime> result =
                scheduleService.getAvailableIntervalsByWorkday(
                        doctor.getPersonId(),
                        Workday.MIERCOLES
                );

        assertThat(result).containsExactly(
                LocalTime.of(8,0),
                LocalTime.of(8,20),
                LocalTime.of(8,40)
        );
    }

    @Test
    void getAvailableIntervalsShouldFailWhenDoctorHasNoSchedule() {

        Doctor doctor = new Doctor(
                UUID.randomUUID(),
                LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(5),
                4,
                true,
                20
        );

        when(doctorRepository.findById(doctor.getPersonId()))
                .thenReturn(Optional.of(doctor));

        assertThrows(
                DoctorScheduleNotFoundException.class,
                () -> scheduleService.getAvailableIntervalsByWorkday(
                        doctor.getPersonId(),
                        Workday.JUEVES
                )
        );
    }
}