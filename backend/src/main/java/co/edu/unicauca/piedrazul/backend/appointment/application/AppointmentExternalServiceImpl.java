package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class AppointmentExternalServiceImpl implements AppointmentExternalService {

    private final AppointmentRepository appointmentRepository;

    public AppointmentExternalServiceImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date){
        return appointmentRepository.findByDoctorIdAndDate(idDoctor, date).stream()
                .filter(a -> a.getAppointmentState() == AppointmentState.AGENDADA)
                .map(a -> new AppointmentSummary(
                        a.getIdAppointment(),
                        a.getIdDoctor(),
                        a.getIdPatient(),
                        a.getDate(),
                        a.getStartTime().getTime(),
                        a.getAppointmentState()
                )).toList();
    }
}
