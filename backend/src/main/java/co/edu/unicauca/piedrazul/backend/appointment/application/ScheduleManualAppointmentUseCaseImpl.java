package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.ScheduleManualAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.UUID;

public class ScheduleManualAppointmentUseCaseImpl implements ScheduleManualAppointmentUseCase {
        private final AppointmentSchedulingService appointmentSchedulingService;
        private final ManualPatientResolutionStrategy manualPatientResolutionStrategy;

    public ScheduleManualAppointmentUseCaseImpl(
                        AppointmentSchedulingService appointmentSchedulingService,
                        ManualPatientResolutionStrategy manualPatientResolutionStrategy) {
                this.appointmentSchedulingService = appointmentSchedulingService;
                this.manualPatientResolutionStrategy = manualPatientResolutionStrategy;
    }

    public ScheduleManualAppointmentUseCaseImpl(
                        AppointmentRepository appointmentRepository,
                        DoctorConfigConsultPort doctorConfigConsultPort,
                        AppointmentService appointmentService,
                        PatientConsultPort patientConsultPort,
                        ApplicationEventPublisher eventPublisher) {
                this(
                                new AppointmentSchedulingService(
                                                appointmentRepository,
                                                doctorConfigConsultPort,
                                                appointmentService,
                                                eventPublisher
                                ),
                                new ManualPatientResolutionStrategy(patientConsultPort)
                );
    }

    public ScheduleManualAppointmentUseCaseImpl(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService,
            PatientConsultPort patientConsultPort) {
        this(
                appointmentRepository,
                doctorConfigConsultPort,
                appointmentService,
                patientConsultPort,
                event -> { }
        );
    }

    // Agendador crea la cita manualmente
    @Override
    public Appointment scheduleManual(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            Gender gender,
            LocalDate birthDate,
            String email,
            String guardianPhone,
            UUID idDoctor,
            Specialty specialty,
            LocalDate date,
            AppointmentTime startTime,
            String performedBy) {
        return appointmentSchedulingService.scheduleManual(
                PatientSchedulingContext.manual(
                        documentType,
                        documentNumber,
                        firstName,
                        lastName,
                        phone,
                        gender,
                        birthDate,
                        email,
                        guardianPhone
                ),
                idDoctor,
                specialty,
                date,
                startTime,
                performedBy,
                manualPatientResolutionStrategy
        );
    }

    public Appointment scheduleManual(
            DocumentType documentType,
            String documentNumber,
            String firstName,
            String lastName,
            String phone,
            Gender gender,
            LocalDate birthDate,
            String email,
            String guardianPhone,
            UUID idDoctor,
            Specialty specialty,
            LocalDate date,
            AppointmentTime startTime) {
        return scheduleManual(
                documentType,
                documentNumber,
                firstName,
                lastName,
                phone,
                gender,
                birthDate,
                email,
                guardianPhone,
                idDoctor,
                specialty,
                date,
                startTime,
                "system"
        );
    }

}
