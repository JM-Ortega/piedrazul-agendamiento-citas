package co.edu.unicauca.piedrazul.backend.patients.application;

import co.edu.unicauca.piedrazul.backend.patients.PatientLookupApi;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PatientLookupService implements PatientLookupApi {

    private final PatientRepository patientRepository;

    public PatientLookupService(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    @Override
    public boolean existsById(UUID patientId) {
        return patientId != null && patientRepository.existsById(patientId);
    }
}