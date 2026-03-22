package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.PatientInfoMapper;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PatientConsultPortImpl implements PatientConsultPort {
    private final PatientModuleApi patientModuleApi;

    public PatientConsultPortImpl(PatientModuleApi patientModuleApi) {
        this.patientModuleApi = patientModuleApi;
    }


    @Override
    public PatientInfo findById(UUID idPatient) {
        return PatientInfoMapper.toRegistrationData(patientModuleApi.findById(idPatient));
    }
}
