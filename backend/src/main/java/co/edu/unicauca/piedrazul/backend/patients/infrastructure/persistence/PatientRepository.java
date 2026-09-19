package co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence;

import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {

    /**
     * Pacientes con su nombre y documento, ordenados por nombre sin distinguir
     * mayúsculas ni tildes.
     *
     * <p>Une con {@code person} en SQL porque esa tabla pertenece al módulo de
     * personas y sus datos no se pueden ordenar ni filtrar desde aquí de otro modo;
     * la relación es de clave primaria compartida (ver convenio de migraciones).
     * El orden es fijo: no admite {@code Sort}.
     */
    @Query(
            value = """
        SELECT p.id AS "id",
               p.identification AS "identification",
               p.first_name AS "firstName",
               p.last_name AS "lastName"
        FROM piedrazul.patient pt
        JOIN piedrazul.person p ON p.id = pt.person_id
        ORDER BY extensions.immutable_unaccent(lower(p.first_name || ' ' || p.last_name)), p.id
        """,
            countQuery = "SELECT COUNT(*) FROM piedrazul.patient",
            nativeQuery = true)
    Page<PatientSummaryProjection> findAllSummaries(Pageable pageable);

    /**
     * Igual que {@link #findAllSummaries(Pageable)}, pero solo los pacientes cuyo
     * nombre completo o documento contenga {@code term}. El término debe venir con
     * {@code \}, {@code %} y {@code _} escapados con {@code \}.
     */
    @Query(
            value = """
        SELECT p.id AS "id",
               p.identification AS "identification",
               p.first_name AS "firstName",
               p.last_name AS "lastName"
        FROM piedrazul.patient pt
        JOIN piedrazul.person p ON p.id = pt.person_id
        WHERE (
                extensions.immutable_unaccent(lower(p.first_name || ' ' || p.last_name))
                    LIKE extensions.immutable_unaccent(lower('%' || :term || '%')) ESCAPE '\\'
                OR p.identification LIKE ('%' || :term || '%') ESCAPE '\\'
              )
        ORDER BY extensions.immutable_unaccent(lower(p.first_name || ' ' || p.last_name)), p.id
        """,
            countQuery = """
        SELECT COUNT(*)
        FROM piedrazul.patient pt
        JOIN piedrazul.person p ON p.id = pt.person_id
        WHERE (
                extensions.immutable_unaccent(lower(p.first_name || ' ' || p.last_name))
                    LIKE extensions.immutable_unaccent(lower('%' || :term || '%')) ESCAPE '\\'
                OR p.identification LIKE ('%' || :term || '%') ESCAPE '\\'
              )
        """,
            nativeQuery = true)
    Page<PatientSummaryProjection> searchSummaries(@Param("term") String term, Pageable pageable);
}
