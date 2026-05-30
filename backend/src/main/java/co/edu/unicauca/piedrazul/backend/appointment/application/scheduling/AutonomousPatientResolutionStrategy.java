package co.edu.unicauca.piedrazul.backend.appointment.application.scheduling;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;

import java.util.Objects;

public class AutonomousPatientResolutionStrategy implements PatientResolutionStrategy {

    private final PatientConsultPort patientConsultPort;

    public AutonomousPatientResolutionStrategy(PatientConsultPort patientConsultPort) {
        this.patientConsultPort = patientConsultPort;
    }

    @Override
    public ResolvedPatient resolve(PatientSchedulingContext context) {
        PatientInfo patientInfo = patientConsultPort.findById(Objects.requireNonNull(context.idPatient(), "El paciente es obligatorio"));
        return new ResolvedPatient(context.idPatient(), patientInfo);
    }
}