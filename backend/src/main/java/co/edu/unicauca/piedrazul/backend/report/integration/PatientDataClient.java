package co.edu.unicauca.piedrazul.backend.report.integration;

import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;
import org.springframework.stereotype.Component;

import java.util.UUID;
@Component
public class PatientDataClient {

    private final PatientModuleApi patientModuleApi;

    public PatientDataClient(PatientModuleApi patientModuleApi) {
        this.patientModuleApi = patientModuleApi;
    }

    // Una sola llamada que trae ambos campos para no hacer dos roundtrips por paciente
    public PatientData getPatientData(UUID idPatient) {
        return patientModuleApi.findById(idPatient).orElse(null);
    }
}
