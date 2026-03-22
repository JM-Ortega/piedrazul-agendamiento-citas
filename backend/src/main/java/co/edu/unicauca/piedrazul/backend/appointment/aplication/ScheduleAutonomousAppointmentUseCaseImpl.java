package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleAutonomousAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ScheduleAutonomousAppointmentUseCaseImpl implements ScheduleAutonomousAppointmentUseCase {
    private final AppointmentRepository appointmentRepository;
    private final PatientConsultPort patientConsultPort;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final AppointmentService appointmentService;

    public ScheduleAutonomousAppointmentUseCaseImpl(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService) {
        this.appointmentRepository   = appointmentRepository;
        this.patientConsultPort      = patientConsultPort;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.appointmentService      = appointmentService;
    }

    // Paciente agenda de forma autónoma por la web
    @Override
    public Appointment scheduleAutonomous(UUID idPatient, UUID idDoctor, Specialty specialty, LocalDate date, AppointmentTime startTime) {
        // 1. Obtiene datos del paciente a través del puerto de salida (módulo de pacientes)
        //PatientInfo patientInfo = patientConsultPort.findById(idPatient);
        String doctorName = doctorConfigConsultPort.getDoctorName(idDoctor);

        // 2. Obtiene configuración del médico a través del puerto de salida
        int intervalMinutes = doctorConfigConsultPort
                .getIntervalMinutesByDoctor(idDoctor);

        // 3. Obtiene citas existentes del médico ese día
        List<Appointment> existingAppointments = appointmentRepository
                .findByDoctorIdAndDate(idDoctor, date);

        // 4. Delega la lógica de negocio al servicio de dominio
        Appointment appointment = appointmentService.scheduleAutonomous(
                doctorName, idPatient, null, idDoctor, specialty,
                date, startTime, intervalMinutes, existingAppointments
        );

        // 5. Persiste a través del puerto de salida
        appointmentRepository.save(appointment);

        return appointment;
    }
}
