package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleManualAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import org.jmolecules.ddd.annotation.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduleManualAppointmentUseCaseImpl implements ScheduleManualAppointmentUseCase {
    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final AppointmentService appointmentService;

    public ScheduleManualAppointmentUseCaseImpl(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService) {
        this.appointmentRepository   = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.appointmentService      = appointmentService;
    }

    // Agendador crea la cita manualmente
    @Override
    public Appointment scheduleManual(PatientInfo patientInfo, UUID idDoctor, Specialty specialty, LocalDate date, AppointmentTime startTime) {
        // 1. Obtiene la configuración del médico a través del puerto de salida
        int intervalMinutes = doctorConfigConsultPort
                .getIntervalMinutesByDoctor(idDoctor);

        // 2. Obtiene las citas existentes del médico ese día a través del puerto de salida
        List<Appointment> existingAppointments = appointmentRepository
                .findByDoctorIdAndDate(idDoctor, date);

        // 3. Delega la lógica de negocio al servicio de dominio
        Appointment appointment = appointmentService.scheduleManual(
                patientInfo, idDoctor, specialty,
                date, startTime, intervalMinutes, existingAppointments
        );

        // 4. Persiste a través del puerto de salida
        appointmentRepository.save(appointment);

        return appointment;
    }
}
