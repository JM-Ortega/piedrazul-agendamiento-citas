package co.edu.unicauca.piedrazul.backend.appointment.application.scheduling;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientProvisioningPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.ResolvedPatient;

/**
 * Resuelve el paciente de una cita agendada por personal de la clínica.
 *
 * <p>El agendamiento nunca crea cuentas ni modifica accesos: solo garantiza que
 * exista el paciente necesario para la cita.
 */
public class ManualPatientResolutionStrategy implements PatientResolutionStrategy {

    private final PatientProvisioningPort patientProvisioningPort;

    public ManualPatientResolutionStrategy(PatientProvisioningPort patientProvisioningPort) {
        this.patientProvisioningPort = patientProvisioningPort;
    }

    @Override
    public ResolvedPatient resolve(PatientSchedulingContext context) {
        PatientSnapshot snapshot = patientProvisioningPort.resolveOrRegister(
                new PatientRegistrationData(
                        context.documentType(),
                        context.documentNumber(),
                        context.firstName(),
                        context.lastName(),
                        context.phone(),
                        context.email(),
                        context.gender(),
                        context.birthDate(),
                        context.guardianPhone()
                )
        );

        // Los datos provienen de la persona ya registrada: si existía, los del
        // formulario no la sobrescriben.
        return new ResolvedPatient(snapshot.idPatient(), snapshot.patientInfo());
    }
}
