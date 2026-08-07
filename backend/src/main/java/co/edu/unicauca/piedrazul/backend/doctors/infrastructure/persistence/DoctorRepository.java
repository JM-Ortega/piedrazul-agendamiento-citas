package co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.proyections.DoctorSpecialtyProjection;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;


public interface DoctorRepository extends JpaRepository<Doctor, UUID> {
    // Buscar doctores por especialidad
    List<Doctor> findBySpecialtiesCode(SpecialtyCode specialty);

    // Buscar solo los doctores que están activos
    List<Doctor> findByStatusTrue();
    
    // Buscar por el ID del doctor
    Doctor findByPersonId(UUID personId);

    List<Doctor> findByPersonIdIn(List<UUID> doctorIds);

    // Verifica si el doctor existe buscandolo por su ID
    boolean existsById(@NonNull UUID id);

    // Devuelve los codigos de todas las especialidades de los doctores activos, sin repetir
    @Query("""
        SELECT DISTINCT s.code
        FROM Doctor d
        JOIN d.specialties s
        WHERE d.status = true
    """)
    List<String> findAllDistinctSpecialtyCodesByActiveDoctors();

    @Query("""
        SELECT DISTINCT d
        FROM Doctor d
        LEFT JOIN FETCH d.specialties
    """)
    List<Doctor> findAllWithSpecialties();

    @Query("""
        SELECT new co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.proyections.DoctorSpecialtyProjection(
            d.personId,
            s.code
        )
        FROM Doctor d
        JOIN d.specialties s
        WHERE d.personId IN :personIds
    """)
    List<DoctorSpecialtyProjection> findSpecialtiesByPersonIds(@Param("personIds") Collection<UUID> personIds);
}
