package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientRegistrationData;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;

/**
 * Define la resolución o alta de pacientes necesaria para el agendamiento. Está
 * separado de {@link PatientConsultPort} porque es una operación de escritura.
 */
public interface PatientProvisioningPort {

    /**
     * Devuelve el paciente del documento indicado, registrándolo si falta. No crea
     * cuentas ni modifica roles.
     */
    PatientSnapshot resolveOrRegister(PatientRegistrationData data);
}
