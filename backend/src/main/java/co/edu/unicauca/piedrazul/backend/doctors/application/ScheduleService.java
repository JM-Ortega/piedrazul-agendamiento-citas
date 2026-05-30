package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleValidationException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorDoesNotWorkException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.ScheduleRepository;
import jakarta.transaction.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class ScheduleService {
    private final ScheduleRepository scheduleRepository;

    public ScheduleService(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    // Crear un horario para un doctor específico
    @Transactional
    public Schedule addSchedule(Doctor doctor, Schedule schedule) {
        if (doctor == null || doctor.getIdDoctor() == null) {
            throw new DoctorScheduleValidationException("Doctor must be provided");
        }

        boolean alreadyExistsForWorkday = scheduleRepository.findByDoctor(doctor).stream()
                .anyMatch(existing -> existing.getWorkday().equals(schedule.getWorkday()));
        if (alreadyExistsForWorkday) {
            throw new DoctorScheduleConflictException("A schedule for " + schedule.getWorkday() + " already exists");
        }

        schedule.setDoctor(doctor);
        return scheduleRepository.save(schedule);
    }

    // Modificar el horario para un doctor en un día específico (Workday)
    @Transactional
    public Schedule updateScheduleByWorkday(Doctor doctor, Workday workday, Schedule newScheduleData) {
        if (doctor == null || doctor.getIdDoctor() == null) {
            throw new RuntimeException("El doctor debe ser proporcionado");
        }

        List<Schedule> schedules = scheduleRepository.findByDoctor(doctor);

        Schedule existingSchedule = schedules.stream()
                .filter(s -> s.getWorkday().equals(workday))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No se encontró horario para este día."));

        // Actualizamos los datos
        existingSchedule.setStartTime(newScheduleData.getStartTime());
        existingSchedule.setEndTime(newScheduleData.getEndTime());

        return scheduleRepository.save(existingSchedule);
    }

    @Transactional
    public void deleteScheduleByWorkday(Doctor doctor, Workday workday) {
        if (doctor == null || doctor.getIdDoctor() == null) {
            throw new IllegalArgumentException("El doctor debe ser proporcionado");
        }

        scheduleRepository.deleteByDoctorAndWorkday(doctor, workday);
    }

    public List<Schedule> getSchedulesByDoctor(Doctor doctor) {
        if (doctor == null || doctor.getIdDoctor() == null) {
            throw new RuntimeException("El doctor debe ser proporcionado");
        }
        return scheduleRepository.findByDoctor(doctor);
    }

    public List<LocalTime> getAvailableIntervalsByWorkday(Doctor doctor, Workday workday) {
        if (doctor == null || doctor.getIdDoctor() == null) {
            throw new RuntimeException("El doctor debe ser proporcionado");
        }

        int appointmentInterval = doctor.getAppointmentInterval();
        if (appointmentInterval <= 0) {
            throw new DoctorScheduleValidationException("El intervalo entre citas médicas debe ser mayor que 0");
        }

        List<Schedule> schedules = scheduleRepository.findByDoctor(doctor).stream()
                .filter(schedule -> schedule.getWorkday().equals(workday))
                .toList();

        if (schedules.isEmpty()) {
            throw new DoctorDoesNotWorkException("El doctor no trabaja los " + workday);
        }
        if (schedules.size() > 1) {
            throw new RuntimeException("El doctor tiene más de un horario para el dia " + workday);
        }

        Schedule workdaySchedule = schedules.getFirst();

        LocalTime startTime = workdaySchedule.getStartTime();
        LocalTime endTime = workdaySchedule.getEndTime();
        if (!startTime.isBefore(endTime)) {
            throw new DoctorScheduleValidationException("Rango de horario inválido para el " + workday);
        }

        List<LocalTime> availableIntervals = new ArrayList<>();
        LocalTime current = startTime;

        // El slot es valido solo si cabe completo dentro del rango laboral.
        while (!current.plusMinutes(appointmentInterval).isAfter(endTime)) {
            availableIntervals.add(current);
            current = current.plusMinutes(appointmentInterval);
        }

        return availableIntervals;
    }
}
