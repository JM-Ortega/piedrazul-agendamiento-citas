package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientRegistryPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.PatientRegistrationMapper;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PatientRegistryPortImpl implements PatientRegistryPort {

    private final PatientModuleApi patientModuleApi;

    public PatientRegistryPortImpl(PatientModuleApi patientModuleApi) {
        this.patientModuleApi = patientModuleApi;
    }

    @Override
    public UUID createPatient(PatientRegistrationData data) {

        PatientData created = patientModuleApi.createPatient(
                PatientRegistrationMapper.mapDocumentType(data.documentType()),
                data.documentNumber(),
                data.firstName(),
                data.lastName(),
                data.phone(),
                data.email(),
                PatientRegistrationMapper.mapGender(data.gender()),
                data.birthDate(),
                data.guardianPhone()
        );

        return created.id();
    }
}