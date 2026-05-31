package co.edu.unicauca.piedrazul.backend.patients;

import java.util.UUID;

public interface PatientLookupApi {

    boolean existsById(UUID patientId);
}