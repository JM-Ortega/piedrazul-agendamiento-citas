package co.edu.unicauca.piedrazul.backend.report.integration;

import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import org.springframework.stereotype.Component;

import java.util.UUID;
@Component
public class PatientDataClient {

    private final PatientModuleApi patientModuleApi;

    public PatientDataClient(PatientModuleApi patientModuleApi) {
        this.patientModuleApi = patientModuleApi;
    }

    public String getPatientFullName(UUID idPatient) {
        return patientModuleApi.findById(idPatient)
                .map(p -> p.firstName() + " " + p.lastName())
                .orElse("Paciente no encontrado");
    }
}
