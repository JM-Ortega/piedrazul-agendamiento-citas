package co.edu.unicauca.piedrazul.backend.appointment.domain.port.output;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;

import java.util.UUID;

public interface PatientConsultPort {
    // Devuelve el PatientInfo snapshot para construir la cita
    PatientInfo findById(UUID idPatient);
}
