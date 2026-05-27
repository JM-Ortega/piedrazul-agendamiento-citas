package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
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
    public List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date, String state){

        List<Appointment> appointments = (state != null)
                ? appointmentRepository.findByDoctorIdAndDateAndState(idDoctor, date, state)
                : appointmentRepository.findByDoctorIdAndDate(idDoctor, date); // trae todas

        return appointments.stream()
                .map(a -> new AppointmentSummary(
                        a.getIdAppointment(),
                        a.getIdPatient(),
                        a.getPatientName(),
                        "",
                        "",
                        a.getIdDoctor(),
                        a.getDoctorName(),
                        a.getDate(),
                        a.getStartTime().getTime(),
                        a.getSpecialty().name(),
                        a.getAppointmentState().name()
                )).toList();
    }


    @Override
    public List<SchedulerAppointmentSummary> findAllByDate(LocalDate date) {

        return appointmentRepository.findAllByDate(date)
                .stream()
                .map(a -> new SchedulerAppointmentSummary(
                        a.getDoctorName(),
                        a.getPatientName(),
                        a.getStartTime().getTime()
                ))
                .toList();
    }

    @Override
    public boolean hasAvailableSlots(LocalDate date){
        return true;
    }

}
