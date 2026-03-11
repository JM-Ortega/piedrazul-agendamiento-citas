package co.edu.unicauca.piedrazul.backend.doctors.model.services;

import co.edu.unicauca.piedrazul.backend.doctors.model.models.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.model.models.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.model.models.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.model.repositories.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.model.repositories.ScheduleRepository;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

public class ScheduleService {
    private final ScheduleRepository scheduleRepository;
    private final DoctorRepository doctorRepository;

    public ScheduleService(ScheduleRepository scheduleRepository, DoctorRepository doctorRepository) {
        this.scheduleRepository = scheduleRepository;
        this.doctorRepository = doctorRepository;
    }

    // Crear un horario para un doctor específico
    @Transactional
    public Schedule addSchedule(UUID idDoctor, Schedule schedule) {
        Doctor doctor = doctorRepository.findById(idDoctor)
                .orElseThrow(() -> new RuntimeException("Doctor not found"));

        schedule.setDoctor(doctor);
        return scheduleRepository.save(schedule);
    }

    // Modificar el horario para un doctor en un día específico (Workday)
    @Transactional
    public Schedule updateScheduleByWorkday(UUID idDoctor, Workday workday, Schedule newScheduleData) {
        // Buscamos el horario existente para ese doctor y ese día
        List<Schedule> schedules = scheduleRepository.findByIdDoctor(idDoctor);

        Schedule existingSchedule = schedules.stream()
                .filter(s -> s.getWorkday().equals(workday))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Schedule not found for this day"));

        // Actualizamos los datos
        existingSchedule.setStartTime(newScheduleData.getStartTime());
        existingSchedule.setEndTime(newScheduleData.getEndTime());

        return scheduleRepository.save(existingSchedule);
    }

    public List<Schedule> getSchedulesByDoctor(UUID idDoctor) {
        return scheduleRepository.findByIdDoctor(idDoctor);
    }
}
