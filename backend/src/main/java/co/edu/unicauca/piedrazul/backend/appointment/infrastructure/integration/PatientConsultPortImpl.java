package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.PatientInfoMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.PatientRegistrationMapper;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
                .orElseThrow(() -> new AppointmentPatientNotFoundException("Paciente con id " + idPatient + " no encontrado"));
    }

    @Override
    public Optional<PatientSnapshot> findByDocumentNumber(String documentNumber) {
        return patientModuleApi.findByDocumentNumber(documentNumber)
                .map(patientData -> new PatientSnapshot(
                        patientData.personId(),
                        PatientInfoMapper.toPatientInfo(patientData)
                ));
    }

    @Override
    public Optional<PatientSnapshot> findByUserId(UUID userId) {
        return patientModuleApi.findByUserId(userId)
                .map(p -> new PatientSnapshot(
                        p.personId(),
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
                null,
                PatientRegistrationMapper.mapGender(data.gender()),
                data.birthDate(),
                data.guardianPhone()
        );

        return created.personId();
    }

    @Override
    public Map<UUID, PatientInfo> findByIds(Set<UUID> patientIds) {
        return patientModuleApi.findByIds(patientIds).stream()
                .collect(Collectors.toMap(PatientData::personId, PatientInfoMapper::toPatientInfo));
    }
}