package co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence;

import java.util.UUID;

/** Fila del listado de pacientes que tienen cuenta de usuario. */
public interface PatientAccountSummaryProjection {

    UUID getId();

    String getIdentification();

    String getFirstName();

    String getLastName();

    UUID getUserId();
}
