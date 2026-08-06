package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientSnapshot;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ListMyAppointmentsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;

import java.util.List;
import java.util.UUID;
import co.edu.unicauca.piedrazul.backend.appointment.exception.*;

public class ListMyAppointmentsUseCaseImpl implements ListMyAppointmentsUseCase {

    private final AppointmentRepository appointmentRepository;
    private final PatientConsultPort patientConsultPort;

    public ListMyAppointmentsUseCaseImpl(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort
    ) {
        this.appointmentRepository = appointmentRepository;
        this.patientConsultPort = patientConsultPort;
    }

    //Se busca el paciente a través de su id de usuario
    @Override
    public List<Appointment> execute(UUID userId) {
        PatientSnapshot patient = patientConsultPort.findByUserId(userId)
                .orElseThrow(() -> new AppointmentPatientNotFoundException("Paciente no encontrado para el userId: " + userId));

        return appointmentRepository.findByPatientId(patient.idPatient());
    }
}