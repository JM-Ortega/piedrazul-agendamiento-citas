package co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.SpecialtyCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, UUID> {
    // Buscar doctores por especialidad
    List<Doctor> findBySpecialtyContaining(SpecialtyCode specialty);

    // Buscar solo los doctores que están activos
    List<Doctor> findByStatusTrue();

    // Buscar por el ID de usuario vinculado
    Doctor findByIdUser(UUID idUser);

    // Buscar por el ID del doctor
    Doctor findByPersonId(UUID personId);

    List<Doctor> findByIdDoctorIn(List<UUID> doctorIds);

    // Verifica si el doctor existe buscandolo por su ID
    boolean existsById(UUID id);

    // Devuelve los codigos de todas las especialidades de los doctores activos, sin repetir
    @Query("""
        SELECT DISTINCT s.code
        FROM Doctor d
        JOIN d.specialties s
        WHERE d.status = true
    """)
    List<String> findAllDistinctSpecialtyCodesByActiveDoctors();

    // Devuelve todas las especialidades de un doctor
    @Query("""
        SELECT s
        FROM Doctor d
        JOIN d.specialty s
        WHERE d.identification = :identification
    """)
    List<SpecialtyCode> findSpecialtiesByIdentification(String identification);

    @Query("""
        SELECT d.personId
        FROM Doctor d
        WHERE d.identification = :identification
    """)
    UUID personIdByIdentification(String identification);
}
