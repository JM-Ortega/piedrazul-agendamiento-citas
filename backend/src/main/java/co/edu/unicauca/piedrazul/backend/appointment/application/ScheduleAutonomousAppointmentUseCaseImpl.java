package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.shared.events.AppointmentCreatedEvent;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientAlreadyScheduledInSpecialtyException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientScheduleTimeConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleAutonomousAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ScheduleAutonomousAppointmentUseCaseImpl implements ScheduleAutonomousAppointmentUseCase {
    private final AppointmentRepository appointmentRepository;
    private final PatientConsultPort patientConsultPort;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final AppointmentService appointmentService;
    private final ApplicationEventPublisher eventPublisher;

    public ScheduleAutonomousAppointmentUseCaseImpl(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService,
            ApplicationEventPublisher eventPublisher) {
        this.appointmentRepository = appointmentRepository;
        this.patientConsultPort = patientConsultPort;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.appointmentService = appointmentService;
        this.eventPublisher = eventPublisher;
    }

    public ScheduleAutonomousAppointmentUseCaseImpl(
            AppointmentRepository appointmentRepository,
            PatientConsultPort patientConsultPort,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService) {
        this(
                appointmentRepository,
                patientConsultPort,
                doctorConfigConsultPort,
                appointmentService,
                event -> { }
        );
    }

    @Override
    public Appointment scheduleAutonomous(
            UUID idPatient,
            UUID idDoctor,
            Specialty specialty,
            LocalDate date,
            AppointmentTime startTime,
            String performedBy) {
        String doctorName = doctorConfigConsultPort.getDoctorName(idDoctor);
        int intervalMinutes = doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor);
        List<Appointment> existingAppointments = appointmentRepository.findByDoctorIdAndDate(idDoctor, date);

        validateUniqueScheduledAppointmentBySpecialty(idPatient, specialty);
        validateNoTimeConflictForPatient(idPatient, date, startTime);

        PatientInfo patient = patientConsultPort.findById(idPatient);
        String patientName = patient.getFirstName() + " " + patient.getLastName();

        Appointment appointment = appointmentService.scheduleAutonomous(
                doctorName,
                idPatient,
                null,
                idDoctor,
                patientName,
                specialty,
                date,
                startTime,
                intervalMinutes,
                existingAppointments
        );

        Appointment saved = appointmentRepository.save(appointment);

        eventPublisher.publishEvent(new AppointmentCreatedEvent(
                saved.getIdAppointment().toString(),
                performedBy
        ));

        return saved;
    }

    public Appointment scheduleAutonomous(
            UUID idPatient,
            UUID idDoctor,
            Specialty specialty,
            LocalDate date,
            AppointmentTime startTime) {
        return scheduleAutonomous(
                idPatient,
                idDoctor,
                specialty,
                date,
                startTime,
                "system"
        );
    }

    private void validateUniqueScheduledAppointmentBySpecialty(UUID idPatient, Specialty specialty) {
        boolean hasScheduledInSameSpecialty = appointmentRepository.findByPatientId(idPatient)
                .stream()
                .anyMatch(appointment -> appointment.getSpecialty() == specialty
                        && appointment.getAppointmentState() == AppointmentState.AGENDADA);

        if (hasScheduledInSameSpecialty) {
            throw new PatientAlreadyScheduledInSpecialtyException(
                    "El paciente ya tiene una cita AGENDADA para la especialidad " + specialty
            );
        }
    }

    private void validateNoTimeConflictForPatient(UUID idPatient, LocalDate date, AppointmentTime startTime) {
        boolean hasTimeConflict = appointmentRepository.findByPatientIdAndDate(idPatient, date)
                .stream()
                .anyMatch(appointment -> appointment.getStartTime().equals(startTime)
                        && appointment.getAppointmentState().isActive());

        if (hasTimeConflict) {
            throw new PatientScheduleTimeConflictException(
                    "El paciente ya tiene una cita activa para la fecha " + date + " a las " + startTime.getTime()
            );
        }
    }
}
