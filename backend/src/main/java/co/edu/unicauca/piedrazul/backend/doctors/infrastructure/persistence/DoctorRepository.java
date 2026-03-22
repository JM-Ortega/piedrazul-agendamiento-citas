package co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, UUID> {
    // Buscar doctores por especialidad
    List<Doctor> findBySpecialty(List<Specialty> specialty);

    // Buscar solo los doctores que están activos
    List<Doctor> findByStatusTrue();

    // Buscar por el ID de usuario vinculado
    Doctor findByIdUser(UUID idUser);

    // Buscar por el ID del doctor
    Doctor findByIdDoctor(UUID idDoctor);

    // Verifica si el doctor existe buscandolo por su ID
    boolean existsById(UUID id);

    // Devuelve todas las especialidades de los doctores activos, si repetir
    @Query("SELECT DISTINCT s FROM Doctor d JOIN d.specialty s WHERE d.status = true")
    List<Specialty> findAllDistinctSpecialtiesByActiveDoctors();
}
