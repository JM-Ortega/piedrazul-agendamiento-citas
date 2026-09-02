package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AppointmentExternalData;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.SchedulerAppointmentSummary;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.GetAvailableSlotsUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// No es un USECASE
@Service
public class AppointmentExternalServiceImpl implements AppointmentExternalService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final GetAvailableSlotsUseCase getAvailableSlotsUseCase;
    private final IsNewPatientUseCase isNewPatientUseCase;
    private final PatientConsultPort patientConsultPort;

    public AppointmentExternalServiceImpl(AppointmentRepository appointmentRepository, DoctorConfigConsultPort doctorConfigConsultPort,
                                          GetAvailableSlotsUseCase getAvailableSlotsUseCase, IsNewPatientUseCase isNewPatientUseCase, PatientConsultPort patientConsultPort) {
        this.appointmentRepository = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.getAvailableSlotsUseCase = getAvailableSlotsUseCase;
        this.isNewPatientUseCase = isNewPatientUseCase;
        this.patientConsultPort = patientConsultPort;
    }

    @Override
    public AppointmentExternalData getAppointmentData(UUID idAppointment) {

        Appointment appointment = appointmentRepository.findById(idAppointment);
        String doctorName = doctorConfigConsultPort.getDoctorName(appointment.getIdDoctor());
        return new AppointmentExternalData(
                appointment.getIdAppointment(),
                appointment.getIdDoctor(),
                doctorName,
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

        String doctorName = doctorConfigConsultPort.getDoctorName(idDoctor);

        // Muchos pacientes distintos — búsqueda en lote, una sola consulta para todos
        Set<UUID> patientIds = appointments.stream().map(Appointment::getIdPatient).collect(Collectors.toSet());
        Map<UUID, PatientInfo> patientsById = patientConsultPort.findByIds(patientIds);

        return appointments.stream()
                .map(a -> {
                    PatientInfo patient = patientsById.get(a.getIdPatient());
                    return new AppointmentSummary(
                            a.getIdAppointment(),
                            a.getIdPatient(),
                            patient.getFirstName() + " " + patient.getLastName(),
                            patient.getDocumentNumber(),
                            patient.getPhone(),
                            a.getIdDoctor(),
                            doctorName,
                            a.getDate(),
                            a.getStartTime().getTime(),
                            a.getSpecialty().name(),
                            a.getAppointmentState().name()
                    );
                }).toList();

    }

    @Override
    public UUID getPattientIdByAppointmentId(UUID appointmentId){
        return appointmentRepository.getPattientIdByAppointmentId(appointmentId);
    }

    @Override
    public List<SchedulerAppointmentSummary> findAllByDate(LocalDate date) {
        List<Appointment> appointments = appointmentRepository.findAllByDate(date)
                .stream()
                .filter(a -> a.getAppointmentState() == AppointmentState.AGENDADA)
                .toList();

        // Aquí sí hay múltiples doctores distintos — usa el método en lote que YA existe en el puerto
        Set<UUID> doctorIds = appointments.stream().map(Appointment::getIdDoctor).collect(Collectors.toSet());
        Map<UUID, String> doctorNamesById = doctorConfigConsultPort.getDoctorInfoByIds(doctorIds.stream().toList())
                .stream()
                .collect(Collectors.toMap(DoctorResponse::id, DoctorResponse::name)); // ajustar a los getters reales

        Set<UUID> patientIds = appointments.stream().map(Appointment::getIdPatient).collect(Collectors.toSet());
        Map<UUID, PatientInfo> patientsById = patientConsultPort.findByIds(patientIds);

        return appointments.stream()
                .map(a -> {
                    PatientInfo patient = patientsById.get(a.getIdPatient());
                    return new SchedulerAppointmentSummary(
                            doctorNamesById.get(a.getIdDoctor()),
                            patient.getFirstName() + " " + patient.getLastName(),
                            a.getStartTime().getTime()
                    );
                }).toList();
    }

    @Override
    public boolean hasAvailableSlots(LocalDate date){
        if (date.isBefore(LocalDate.now())) {
            return false;
        }

        List<UUID> idsActiveDoctors = doctorConfigConsultPort.getActiveDoctorIds();
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
    public boolean hasScheduledAppointments(UUID doctorID){
        List<Appointment> appointments = appointmentRepository.findByDoctorIdAndState(doctorID, "AGENDADA");
        return !appointments.isEmpty();
    }

    @Override
    public boolean isNewPatient(UUID patientId){
        return isNewPatientUseCase.isNewPatient(patientId);
    }
}
