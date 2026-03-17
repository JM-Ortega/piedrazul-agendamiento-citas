package co.edu.unicauca.piedrazul.backend.doctors.model.repositories;

import co.edu.unicauca.piedrazul.backend.doctors.model.models.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.model.models.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
    // Obtener todos los horarios de un doctor específico
    List<Schedule> findByIdDoctor(Doctor doctor);

    // Buscar horarios por el ID del doctor
    List<Schedule> findByIdDoctor(UUID idDoctor);

    // Buscar horarios de un doctor en un día de trabajo específico (ej. LUNES)
    List<Schedule> findByIdDoctorAndWorkday(Doctor doctor, Object workday);
}