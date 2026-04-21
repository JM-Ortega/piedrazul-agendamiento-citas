package co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
    // Obtener todos los horarios de un doctor específico
    List<Schedule> findByDoctor(Doctor doctor);

    // Buscar horarios de un doctor en un día de trabajo específico (ej. LUNES)
    List<Schedule> findByDoctorAndWorkday(Doctor doctor, Object workday);

    void deleteByDoctorAndWorkday(Doctor doctor, Workday workday);
}