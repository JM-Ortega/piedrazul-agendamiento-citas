package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientProvisioningPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.PatientInfoMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.PatientRegistrationMapper;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.RegisterPatientCommand;
import org.springframework.stereotype.Component;

@Component
public class PatientProvisioningPortImpl implements PatientProvisioningPort {

    private final PatientModuleApi patientModuleApi;

    public PatientProvisioningPortImpl(PatientModuleApi patientModuleApi) {
        this.patientModuleApi = patientModuleApi;
    }

    @Override
    public PatientSnapshot resolveOrRegister(PatientRegistrationData data) {
        PatientData patient = patientModuleApi.resolveOrRegisterPatient(
                new RegisterPatientCommand(
                        PatientRegistrationMapper.mapDocumentType(data.documentType()),
                        data.documentNumber(),
                        data.firstName(),
                        data.lastName(),
                        data.phone(),
                        data.email(),
                        PatientRegistrationMapper.mapGender(data.gender()),
                        data.birthDate(),
                        data.guardianPhone()
                )
        );

        return new PatientSnapshot(patient.personId(), PatientInfoMapper.toPatientInfo(patient));
    }
}
