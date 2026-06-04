package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// No es un USECASE
@Service
public class AppointmentExternalServiceImpl implements AppointmentExternalService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorExternalService doctorExternalService;
    private final GetAvailableSlotsUseCase getAvailableSlotsUseCase;
    private final IsNewPatientUseCase isNewPatientUseCase;

    public AppointmentExternalServiceImpl(AppointmentRepository appointmentRepository, DoctorExternalService doctorExternalService,
                                          GetAvailableSlotsUseCase getAvailableSlotsUseCase, IsNewPatientUseCase isNewPatientUseCase) {
        this.appointmentRepository = appointmentRepository;
        this.doctorExternalService = doctorExternalService;
        this.getAvailableSlotsUseCase = getAvailableSlotsUseCase;
        this.isNewPatientUseCase = isNewPatientUseCase;
    }

    @Override
    public AppointmentExternalData getAppointmentData(UUID idAppointment) {

        Appointment appointment = appointmentRepository.findById(idAppointment);
        return new AppointmentExternalData(
                appointment.getIdAppointment(),
                appointment.getIdDoctor(),
                appointment.getDoctorName(),
                appointment.getIdPatient(),
                appointment.getAppointmentState().name(),
                appointment.getDate()
        );
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
    public UUID getPattientIdByAppointmentId(UUID appointmentId){
        return appointmentRepository.getPattientIdByAppointmentId(appointmentId);
    }

    @Override
    public List<SchedulerAppointmentSummary> findAllByDate(LocalDate date) {

        return appointmentRepository.findAllByDate(date)
                .stream()
                .filter(a -> a.getAppointmentState() == AppointmentState.AGENDADA)
                .map(a -> new SchedulerAppointmentSummary(
                        a.getDoctorName(),
                        a.getPatientName(),
                        a.getStartTime().getTime()
                ))
                .toList();
    }

    @Override
    public boolean hasAvailableSlots(LocalDate date){
        if (date.isBefore(LocalDate.now())) {
            return false;
        }

        List<UUID> idsActiveDoctors = doctorExternalService.getActiveDoctorIds();
        for (UUID id : idsActiveDoctors){
            try {
                List<AppointmentTime> availableSlots = getAvailableSlotsUseCase.getAvailableSlots(id, date);
                if (!availableSlots.isEmpty()) {
                    return true;
                }
            } catch (RuntimeException e) {
                // El doctor no trabaja este día. Ignoramos el error y el for pasa al siguiente doctor.
            }
        }
        return false;
    }

    @Override
    public boolean isNewPatient(UUID patientId){
        return isNewPatientUseCase.isNewPatient(patientId);
    }
}
