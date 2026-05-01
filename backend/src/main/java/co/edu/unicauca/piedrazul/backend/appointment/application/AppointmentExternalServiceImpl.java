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
    public List<AppointmentSummary> findByDoctorAndDate(UUID idDoctor, LocalDate date, String state){
        return appointmentRepository.findByDoctorIdAndDateAndState(idDoctor, date, state).stream()
                .map(a -> new AppointmentSummary(
                        a.getIdAppointment(),
                        a.getIdPatient(),
                        a.getPatientName(),
                        // CORREGIDO: patientInfo es null cuando se reconstruye desde la BD
                        // (el mapper lo reconstruye con null intencionalmente porque PatientInfo
                        // no se persiste en la entidad). Se usan cadenas vacías como valores
                        // seguros; si en el futuro se necesitan, se deberá persistir esos campos
                        // en AppointmentEntity o consultar el módulo de paciente.
                        "",   // document — no persistido en AppointmentEntity
                        "",   // phoneNumber — no persistido en AppointmentEntity
                        a.getIdDoctor(),
                        a.getDoctorName(),
                        a.getDate(),
                        a.getStartTime().getTime(),
                        a.getSpecialty().name(),
                        a.getAppointmentState().name()
                )).toList();
    }

}
