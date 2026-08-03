package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.UpdateAppointmentStatusUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.ClinicalHistoryPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;

import java.time.LocalDate;
import java.util.UUID;

public class UpdateAppointmentStatusUseCaseImpl implements UpdateAppointmentStatusUseCase {
    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final ClinicalHistoryPort clinicalHistoryPort;

    public UpdateAppointmentStatusUseCaseImpl(AppointmentRepository appointmentRepository, DoctorConfigConsultPort doctorConfigConsultPort, ClinicalHistoryPort clinicalHistoryPort) {
        this.appointmentRepository = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.clinicalHistoryPort = clinicalHistoryPort;
    }

    @Override
    public void markAsAttended(UUID appointmentId, String clinicalHistoryDescription) {
        // Obtener la cita por ID
        Appointment appointment = appointmentRepository.findById(appointmentId);

        // Actualizar el estado a ATENDIDA
        appointment.changeState(AppointmentState.ATENDIDA);

        // Guardar la cita actualizada
        appointmentRepository.save(appointment);

        if(clinicalHistoryDescription != null && !clinicalHistoryDescription.isBlank()){
            String doctorName = doctorConfigConsultPort.getDoctorName(appointment.getIdDoctor());
            clinicalHistoryPort.registerClinicalHistory(appointment.getIdAppointment(), appointment.getIdPatient(), doctorName, clinicalHistoryDescription, LocalDate.now());

        }
    }

    @Override
    public void markAsUnassisted(UUID appointmentId) {
        // Obtener la cita por ID
        Appointment appointment = appointmentRepository.findById(appointmentId);

        // Actualizar el estado a NO ASISTIDA
        appointment.changeState(AppointmentState.NO_ASISTIO);

        // Guardar la cita actualizada
        appointmentRepository.save(appointment);
    }
}
