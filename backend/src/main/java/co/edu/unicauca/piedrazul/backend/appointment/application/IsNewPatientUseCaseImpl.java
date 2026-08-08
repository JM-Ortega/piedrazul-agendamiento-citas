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
     * cita antes o no, se pone solo el estado de atendida porque  en caso de qeu tenga medicina general esta debe estar
     * si o si atendida para que puedan dejarle agendar citas nuevas, o sea si minimo tiene una cita atendida ya no es
     * nuevo, no basta con tener citas agendadas
     * @param patientId
     * @return true si es nuevo y false si no lo es
     */
    @Override
    public boolean isNewPatient(UUID patientId) {
        if (!patientConsultPort.existsById(patientId)) {
            return true;
        }

        return !appointmentRepository.existsByPatientIdAndStates(
                patientId,
                EnumSet.of(AppointmentState.ATENDIDA)
        );
    }
}