package co.edu.unicauca.piedrazul.backend.doctors.model.repositories;

import co.edu.unicauca.piedrazul.backend.doctors.model.models.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.model.models.Specialty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, UUID> {
    // Buscar doctores por especialidad
    List<Doctor> findBySpecialty(Specialty specialty);

    // Buscar solo los doctores que están activos
    List<Doctor> findByStatusTrue();

    // Buscar por el ID de usuario vinculado
    Doctor findByIdUser(UUID idUser);

    // Verifica si el doctor existe buscandolo por su ID
    boolean existsById(UUID id);

}
