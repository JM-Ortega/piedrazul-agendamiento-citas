package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.PatientInfoMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.PatientRegistrationMapper;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentPatientNotFoundException;

@Component
public class PatientConsultPortImpl implements PatientConsultPort {

    private final PatientModuleApi patientModuleApi;

    public PatientConsultPortImpl(PatientModuleApi patientModuleApi) {
        this.patientModuleApi = patientModuleApi;
    }

    @Override
    public PatientInfo findById(UUID idPatient) {
        return patientModuleApi.findById(idPatient)
                .map(PatientInfoMapper::toPatientInfo)
                .orElseThrow(() -> new AppointmentPatientNotFoundException("Patient not found: " + idPatient));
    }

    @Override
    public Optional<PatientSnapshot> findByDocumentNumber(String documentNumber) {
        return patientModuleApi.findByDocumentNumber(documentNumber)
                .map(patientData -> new PatientSnapshot(
                        patientData.id(),
                        PatientInfoMapper.toPatientInfo(patientData)
                ));
    }

    @Override
    public Optional<PatientSnapshot> findByUserId(UUID userId) {
        return patientModuleApi.findByUserId(userId)
                .map(p -> new PatientSnapshot(
                        p.id(),
                        PatientInfoMapper.toPatientInfo(p)
                ));
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