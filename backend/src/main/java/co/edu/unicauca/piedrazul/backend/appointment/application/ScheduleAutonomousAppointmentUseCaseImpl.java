package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.AutonomousPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleAutonomousAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.UUID;

public class ScheduleAutonomousAppointmentUseCaseImpl implements ScheduleAutonomousAppointmentUseCase {
        private final AppointmentSchedulingService appointmentSchedulingService;
        private final AutonomousPatientResolutionStrategy autonomousPatientResolutionStrategy;

    public ScheduleAutonomousAppointmentUseCaseImpl(
                        AppointmentSchedulingService appointmentSchedulingService,
                        AutonomousPatientResolutionStrategy autonomousPatientResolutionStrategy) {
                this.appointmentSchedulingService = appointmentSchedulingService;
                this.autonomousPatientResolutionStrategy = autonomousPatientResolutionStrategy;
    }

    public ScheduleAutonomousAppointmentUseCaseImpl(
                        AppointmentRepository appointmentRepository,
                        PatientConsultPort patientConsultPort,
                        DoctorConfigConsultPort doctorConfigConsultPort,
                        AppointmentService appointmentService,
                        ApplicationEventPublisher eventPublisher) {
                this(
                                new AppointmentSchedulingService(
                                                appointmentRepository,
                                                doctorConfigConsultPort,
                                                appointmentService,
                                                eventPublisher,
                                                new IsNewPatientUseCaseImpl(
                                                        appointmentRepository,
                                                        patientConsultPort
                                                )
                                ),
                                new AutonomousPatientResolutionStrategy(patientConsultPort)
                );
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
        return appointmentSchedulingService.scheduleAutonomous(
                PatientSchedulingContext.autonomous(idPatient),
                idDoctor,
                specialty,
                date,
                startTime,
                performedBy,
                autonomousPatientResolutionStrategy
        );
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
}
