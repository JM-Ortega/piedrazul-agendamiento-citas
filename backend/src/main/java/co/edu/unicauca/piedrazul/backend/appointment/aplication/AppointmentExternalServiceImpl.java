package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.dto.AppointmentExternalData;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AppointmentExternalServiceImpl implements AppointmentExternalService {

    private AppointmentRepository appointmentRepository;

    public AppointmentExternalServiceImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public AppointmentExternalData getAppointmentData(UUID idAppointment) {

        Appointment appointment = appointmentRepository.findById(idAppointment)
                .orElseThrow(() -> new IllegalArgumentException("No existe una una cita para el id: " + idAppointment));

        return new AppointmentExternalData(
                appointment.getIdAppointment(),
                appointment.getIdDoctor(),
                appointment.getDoctorName(),
                appointment.getIdPatient(),
                appointment.getAppointmentState().name(),
                appointment.getDate(),
                appointment.getStartTime().getTime() // ← aquí tengo una duda
        );
    }

}
