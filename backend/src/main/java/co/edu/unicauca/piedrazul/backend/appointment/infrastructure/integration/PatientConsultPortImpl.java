package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
//import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.PatientInfoMapper;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

@Component
public class PatientConsultPortImpl implements PatientConsultPort {

    private final PatientModuleApi patientModuleApi;

    public PatientConsultPortImpl(PatientModuleApi patientModuleApi) {
        this.patientModuleApi = patientModuleApi;
    }

    @Override
    public PatientInfo findById(UUID idPatient) {
        LocalDate fecha = LocalDate.of(2026, 3, 22);
        PatientInfo p = PatientInfo.of(DocumentType.CEDULA, "123456789", "John Doe", "555-1234", "123123", Gender.FEMALE,
                fecha, "asda", "asda");
        return p;
    }
}
