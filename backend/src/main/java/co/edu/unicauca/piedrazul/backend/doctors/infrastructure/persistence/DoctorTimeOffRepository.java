package co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.doctors.domain.DoctorTimeOff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DoctorTimeOffRepository extends JpaRepository<DoctorTimeOff, UUID> {

    List<DoctorTimeOff> findByDoctorIdOrderByStartDateDesc(UUID doctorId);

    // Descansos que se cruzan con el rango [from, to]
    List<DoctorTimeOff> findByDoctorIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID doctorId, LocalDate to, LocalDate from);

    boolean existsByDoctorIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            UUID doctorId, LocalDate to, LocalDate from);
}
