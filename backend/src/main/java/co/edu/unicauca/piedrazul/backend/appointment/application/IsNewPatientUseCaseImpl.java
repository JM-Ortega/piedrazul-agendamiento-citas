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

    /**
     * Si el paciente no exite en la bd significa que nunca antes se ha registrado una cita para ese paciente
     * por lo tanto automaticamente es nuevo, por otro lado si si existe toca que ver entonces si ya ha registrado una
     * cita antes o no
     * @param patientId
     * @return true si es nuevo y false si no lo es
     */
    @Override
    public boolean isNewPatient(UUID patientId) {
        try {
            patientConsultPort.findById(patientId);
        } catch (Exception e) {
            return true;
        }

        return !appointmentRepository.existsByPatientIdAndStates(
                patientId,
                EnumSet.of(AppointmentState.AGENDADA, AppointmentState.ATENDIDA)
        );
    }
}