package co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence;

import java.util.UUID;

/** Fila del listado de pacientes: solo lo necesario para identificarlos. */
public interface PatientSummaryProjection {

    UUID getId();

    String getIdentification();

    String getFirstName();

    String getLastName();
}
