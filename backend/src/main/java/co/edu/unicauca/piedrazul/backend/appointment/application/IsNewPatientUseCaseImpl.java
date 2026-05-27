package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;

import java.util.EnumSet;
import java.util.UUID;

public class IsNewPatientUseCaseImpl implements IsNewPatientUseCase {

    private final AppointmentRepository appointmentRepository;
    private final PatientConsultPort patientConsultPort;

    public IsNewPatientUseCaseImpl(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort) {
        this.appointmentRepository = appointmentRepository;
        this.patientConsultPort = patientConsultPort;
    }

    @Override
    public boolean isNewPatient(UUID patientId) {
        patientConsultPort.findById(patientId);

        return !appointmentRepository.existsByPatientIdAndStates(
                patientId,
                EnumSet.of(AppointmentState.AGENDADA, AppointmentState.ATENDIDA)
        );
    }
}