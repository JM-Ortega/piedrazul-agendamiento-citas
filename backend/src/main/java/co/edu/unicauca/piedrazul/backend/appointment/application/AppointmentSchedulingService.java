package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.PatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PersonConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.AppointmentSchedulingRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.ResolvedPatient;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientAlreadyScheduledInSpecialtyException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientScheduleTimeConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.events.AppointmentScheduledEvent;
import co.edu.unicauca.piedrazul.backend.appointment.exception.FirstAppointmentMustBeGeneralMedicineException;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.shared.events.audit.AppointmentCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class AppointmentSchedulingService {

    private final AppointmentRepository appointmentRepository;
    private final DoctorConfigConsultPort doctorConfigConsultPort;
    private final AppointmentService appointmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final IsNewPatientUseCase isNewPatientUseCase;

    public AppointmentSchedulingService(
            AppointmentRepository appointmentRepository,
            DoctorConfigConsultPort doctorConfigConsultPort,
            AppointmentService appointmentService,
            ApplicationEventPublisher eventPublisher,
            IsNewPatientUseCase isNewPatientUseCase) {
        this.appointmentRepository = appointmentRepository;
        this.doctorConfigConsultPort = doctorConfigConsultPort;
        this.appointmentService = appointmentService;
        this.eventPublisher = eventPublisher;
        this.isNewPatientUseCase = isNewPatientUseCase;
    }

    @Transactional
    public void scheduleManual(
            PatientSchedulingContext patientContext,
            UUID idDoctor,
            SpecialtyCode specialty,
            LocalDate date,
            AppointmentTime startTime,
            UUID performedBy,
            PatientResolutionStrategy patientResolutionStrategy) {
        schedule(patientContext, idDoctor, specialty, date, startTime, performedBy, patientResolutionStrategy, true);
    }

    @Transactional
    public void scheduleAutonomous(
            PatientSchedulingContext patientContext,
            UUID idDoctor,
            SpecialtyCode specialty,
            LocalDate date,
            AppointmentTime startTime,
            UUID performedBy,
            PatientResolutionStrategy patientResolutionStrategy) {
        schedule(patientContext, idDoctor, specialty, date, startTime, performedBy, patientResolutionStrategy, false);
    }

    private Appointment schedule(
            PatientSchedulingContext patientContext,
            UUID idDoctor,
            SpecialtyCode specialty,
            LocalDate date,
            AppointmentTime startTime,
            UUID performedBy,
            PatientResolutionStrategy patientResolutionStrategy,
            boolean manualFlow) {

        int intervalMinutes = doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor);
        String doctorName = doctorConfigConsultPort.getDoctorName(idDoctor);
        List<Appointment> existingAppointments = appointmentRepository.findByDoctorIdAndDate(idDoctor, date);

        // Srategy
        ResolvedPatient resolvedPatient = patientResolutionStrategy.resolve(patientContext);

        validateNewPatientFirstAppointmentSpecialty(resolvedPatient.idPatient(), specialty);

        validateUniqueScheduledAppointmentBySpecialty(resolvedPatient.idPatient(), specialty);
        validateNoTimeConflictForPatient(resolvedPatient.idPatient(), date, startTime);

        PatientInfo patientInfo = resolvedPatient.patientInfo();
        String patientName = buildPatientName(patientInfo);

        // Validación contra el dominio
        AppointmentSchedulingRequest request =
                new AppointmentSchedulingRequest(
                        idDoctor,
                        resolvedPatient.idPatient(),
                        specialty,
                        date,
                        startTime
                );

        Appointment appointment =
                manualFlow
                        ? appointmentService.scheduleManual(
                        request,
                        intervalMinutes,
                        existingAppointments)
                        : appointmentService.scheduleAutonomous(
                        request,
                        intervalMinutes,
                        existingAppointments);


        Appointment saved = appointmentRepository.save(appointment);

        eventPublisher.publishEvent(new AppointmentCreatedEvent(saved.getIdAppointment(), performedBy));

        eventPublisher.publishEvent(
                new AppointmentScheduledEvent(
                        saved.getIdAppointment(),
                        resolvedPatient.idPatient(),
                        patientName,
                        patientInfo.getPhone(),
                        patientInfo.getEmail(),
                        idDoctor,
                        doctorName,
                        date,
                        startTime.getTime(),
                        specialty.name(),
                        performedBy
                )
        );
        return saved;
    }

    private String buildPatientName(PatientInfo patientInfo) {
        return patientInfo.getFirstName() + " " + patientInfo.getLastName();
    }

    private void validateNewPatientFirstAppointmentSpecialty(UUID idPatient, SpecialtyCode specialty) {
        boolean isNewPatient = isNewPatientUseCase.isNewPatient(idPatient);
        if (isNewPatient && specialty != SpecialtyCode.MEDICINA_GENERAL) {
            throw new FirstAppointmentMustBeGeneralMedicineException(
                    "La primera cita de un paciente nuevo debe ser con MEDICINA_GENERAL"
            );
        }
    }

    private void validateUniqueScheduledAppointmentBySpecialty(UUID idPatient, SpecialtyCode specialty) {
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