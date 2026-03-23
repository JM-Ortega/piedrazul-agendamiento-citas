package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
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
            throw new RuntimeException("Doctor must be provided");
        }

        boolean alreadyExistsForWorkday = scheduleRepository.findByDoctor(doctor).stream()
                .anyMatch(existing -> existing.getWorkday().equals(schedule.getWorkday()));
        if (alreadyExistsForWorkday) {
            throw new RuntimeException("A schedule for " + schedule.getWorkday() + " already exists");
        }

        schedule.setDoctor(doctor);
        return scheduleRepository.save(schedule);
    }

    // Modificar el horario para un doctor en un día específico (Workday)
    @Transactional
    public Schedule updateScheduleByWorkday(Doctor doctor, Workday workday, Schedule newScheduleData) {
        if (doctor == null || doctor.getIdDoctor() == null) {
            throw new RuntimeException("Doctor must be provided");
        }

        List<Schedule> schedules = scheduleRepository.findByDoctor(doctor);

        Schedule existingSchedule = schedules.stream()
                .filter(s -> s.getWorkday().equals(workday))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Schedule not found for this day"));

        // Actualizamos los datos
        existingSchedule.setStartTime(newScheduleData.getStartTime());
        existingSchedule.setEndTime(newScheduleData.getEndTime());

        return scheduleRepository.save(existingSchedule);
    }

    public List<Schedule> getSchedulesByDoctor(Doctor doctor) {
        if (doctor == null || doctor.getIdDoctor() == null) {
            throw new RuntimeException("Doctor must be provided");
        }
        return scheduleRepository.findByDoctor(doctor);
    }

    public List<LocalTime> getAvailableIntervalsByWorkday(Doctor doctor, Workday workday) {
        if (doctor == null || doctor.getIdDoctor() == null) {
            throw new RuntimeException("Doctor must be provided");
        }

        int appointmentInterval = doctor.getAppointmentInterval();
        if (appointmentInterval <= 0) {
            throw new RuntimeException("Doctor appointment interval must be greater than 0");
        }

        List<Schedule> schedules = scheduleRepository.findByDoctor(doctor).stream()
                .filter(schedule -> schedule.getWorkday().equals(workday))
                .toList();

        if (schedules.isEmpty()) {
            throw new RuntimeException("Doctor does not work on " + workday);
        }
        if (schedules.size() > 1) {
            throw new RuntimeException("Doctor has more than one schedule for " + workday);
        }

        Schedule workdaySchedule = schedules.getFirst();

        LocalTime startTime = workdaySchedule.getStartTime();
        LocalTime endTime = workdaySchedule.getEndTime();
        if (!startTime.isBefore(endTime)) {
            throw new RuntimeException("Invalid schedule range for " + workday);
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
