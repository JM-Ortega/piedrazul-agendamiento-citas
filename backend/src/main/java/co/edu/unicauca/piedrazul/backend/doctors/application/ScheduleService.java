package co.edu.unicauca.piedrazul.backend.doctors.application;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.ScheduleRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.shared.enums.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleConflictException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleNotFoundException;
import co.edu.unicauca.piedrazul.backend.doctors.exception.DoctorScheduleValidationException;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import jakarta.transaction.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ScheduleService {
    private final DoctorRepository doctorRepository;

    public ScheduleService(DoctorRepository doctorRepository) {
        this.doctorRepository = doctorRepository;
    }

    // Metodo unico para actualizar y crear horarios
    @Transactional
    public void updateSchedule(
            UUID doctorId,
            ScheduleRequest request
    ) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        doctor.updateSchedule(
                request.workday(),
                request.startTime(),
                request.endTime()
        );

        doctorRepository.save(doctor);
    }

    @Transactional
    public void deleteScheduleByWorkday(UUID doctorId, Workday workday) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));
        doctor.removeSchedule(workday);

        doctorRepository.save(doctor);
    }

    public List<Schedule> getSchedulesByDoctor(UUID doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        return doctor.getSchedules().stream().toList();
    }

    public List<LocalTime> getAvailableIntervalsByWorkday(UUID doctorId, Workday workday) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new DoctorNotFoundException("Doctor no encontrado"));

        int appointmentInterval = doctor.getAppointmentInterval();
        if (appointmentInterval <= 0) {
            throw new DoctorScheduleValidationException("El intervalo entre citas médicas debe ser mayor que 0");
        }

        List<Schedule> schedules = doctor.getSchedules().stream()
                .filter(schedule -> schedule.getWorkday().equals(workday))
                .toList();

        if (schedules.isEmpty()) {
            throw new DoctorScheduleNotFoundException("El doctor no trabaja el " + workday);
        }
        if (schedules.size() > 1) {
            throw new DoctorScheduleConflictException("El doctor tiene más de un horario para el " + workday);
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
