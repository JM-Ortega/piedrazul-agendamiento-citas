package co.edu.unicauca.piedrazul.backend.appointment.application.scheduling;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;

import java.util.Optional;
import java.util.UUID;

public class ManualPatientResolutionStrategy implements PatientResolutionStrategy {

    private final PatientConsultPort patientConsultPort;

    public ManualPatientResolutionStrategy(PatientConsultPort patientConsultPort) {
        this.patientConsultPort = patientConsultPort;
    }

    @Override
    public ResolvedPatient resolve(PatientSchedulingContext context) {
        Optional<PatientSnapshot> existingPatient = patientConsultPort.findByDocumentNumber(context.documentNumber());

        if (existingPatient.isPresent()) {
            PatientSnapshot snapshot = existingPatient.get();
            return new ResolvedPatient(snapshot.idPatient(), snapshot.patientInfo());
        }

        PatientInfo patientInfo = PatientInfo.of(
                context.documentType(),
                context.documentNumber(),
                context.firstName(),
                context.lastName(),
                context.phone(),
                context.gender(),
                context.birthDate(),
                context.email(),
                context.guardianPhone()
        );

        UUID idPatient = patientConsultPort.createPatient(new PatientRegistrationData(
                context.documentType(),
                context.documentNumber(),
                context.firstName(),
                context.lastName(),
                context.phone(),
                context.email(),
                context.gender(),
                context.birthDate(),
                context.guardianPhone()
        ));

        return new ResolvedPatient(idPatient, patientInfo);
    }
}