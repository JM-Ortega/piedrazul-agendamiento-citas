package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleValidationException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    private ScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        scheduleService = new ScheduleService(scheduleRepository);
    }

    @Test
    void addScheduleShouldThrowWhenDoctorIsNull() {
        Schedule schedule = new Schedule(null, LocalTime.of(8, 0), LocalTime.of(10, 0), Workday.LUNES);

        assertThatThrownBy(() -> scheduleService.addSchedule(null, schedule))
                .isInstanceOf(DoctorScheduleValidationException.class)
                .hasMessageContaining("Doctor must be provided");

        verify(scheduleRepository, never()).save(any(Schedule.class));
    }

    @Test
    void addScheduleShouldThrowWhenScheduleAlreadyExistsForWorkday() {
        Doctor doctor = createDoctorFixture(30);
        Schedule existing = new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(12, 0), Workday.LUNES);
        Schedule incoming = new Schedule(doctor, LocalTime.of(13, 0), LocalTime.of(17, 0), Workday.LUNES);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> scheduleService.addSchedule(doctor, incoming))
                .isInstanceOf(DoctorScheduleConflictException.class)
                .hasMessageContaining("already exists");

        verify(scheduleRepository, never()).save(any(Schedule.class));
    }

    @Test
    void updateScheduleByWorkdayShouldUpdateTimes() {
        Doctor doctor = createDoctorFixture(30);
        Schedule existing = new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(12, 0), Workday.MARTES);
        Schedule newData = new Schedule(doctor, LocalTime.of(9, 0), LocalTime.of(13, 0), Workday.MARTES);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of(existing));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Schedule updated = scheduleService.updateScheduleByWorkday(doctor, Workday.MARTES, newData);

        assertThat(updated.getStartTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(updated.getEndTime()).isEqualTo(LocalTime.of(13, 0));
        verify(scheduleRepository).save(existing);
    }

    @Test
    void getAvailableIntervalsByWorkdayShouldReturnAllValidSlots() {
        Doctor doctor = createDoctorFixture(30);
        Schedule monday = new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(10, 0), Workday.LUNES);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of(monday));

        List<LocalTime> intervals = scheduleService.getAvailableIntervalsByWorkday(doctor, Workday.LUNES);

        assertThat(intervals).containsExactly(
                LocalTime.of(8, 0),
                LocalTime.of(8, 30),
                LocalTime.of(9, 0),
                LocalTime.of(9, 30)
        );
    }

    @Test
    void getAvailableIntervalsByWorkdayShouldThrowWhenRangeIsInvalid() {
        Doctor doctor = createDoctorFixture(30);
        Schedule monday = new Schedule(doctor, LocalTime.of(10, 0), LocalTime.of(8, 0), Workday.LUNES);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of(monday));

        assertThatThrownBy(() -> scheduleService.getAvailableIntervalsByWorkday(doctor, Workday.LUNES))
                .isInstanceOf(DoctorScheduleValidationException.class)
                .hasMessageContaining("Rango de horario inválido para el LUNES");
    }

    @Test
    void getAvailableIntervalsByWorkdayShouldThrowWhenThereAreDuplicateSchedulesForDay() {
        Doctor doctor = createDoctorFixture(30);
        Schedule first = new Schedule(doctor, LocalTime.of(8, 0), LocalTime.of(10, 0), Workday.MIERCOLES);
        Schedule second = new Schedule(doctor, LocalTime.of(10, 0), LocalTime.of(12, 0), Workday.MIERCOLES);

        when(scheduleRepository.findByDoctor(doctor)).thenReturn(List.of(first, second));

        assertThatThrownBy(() -> scheduleService.getAvailableIntervalsByWorkday(doctor, Workday.MIERCOLES))
                .isInstanceOf(DoctorScheduleConflictException.class)
                .hasMessageContaining("El doctor tiene más de un horario para el MIERCOLES");
    }

    @Test
    void getAvailableIntervalsByWorkdayShouldThrowWhenAppointmentIntervalIsNotPositive() {
        Doctor doctor = createDoctorFixture(0);

        assertThatThrownBy(() -> scheduleService.getAvailableIntervalsByWorkday(doctor, Workday.LUNES))
                .isInstanceOf(DoctorScheduleValidationException.class)
                .hasMessageContaining("El intervalo entre citas médicas debe ser mayor que 0");

        verify(scheduleRepository, never()).findByDoctor(any(Doctor.class));
    }

    private Doctor createDoctorFixture(int appointmentInterval) {
        Doctor doctor = new Doctor();
        doctor.setIdDoctor(UUID.randomUUID());
        doctor.setAppointmentInterval(appointmentInterval);
        return doctor;
    }
}