package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.PatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CancelAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.RescheduleAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentAccessDeniedException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentStateConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.CancelAppointmentNotAllowedException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Reagendar no tiene reglas propias: une cancelar y agendar (manual) en una sola transacción.
 *
 * <p>Se cancela primero para que las reglas del agendamiento vean la original como CANCELADA
 * (libera su slot y el horario del paciente). Si agendar falla, el rollback deshace la cancelación,
 * y los eventos (de la cita nueva y el aviso de reagendamiento) solo se consumen tras el commit.
 */
public class RescheduleAppointmentUseCaseImpl implements RescheduleAppointmentUseCase {

    private final AppointmentRepository appointmentRepository;
    private final CancelAppointmentUseCase cancelAppointmentUseCase;
    private final AppointmentSchedulingService appointmentSchedulingService;
    private final PatientResolutionStrategy patientByIdResolutionStrategy;
    private final AppointmentChangeEvents changeEvents;

    public RescheduleAppointmentUseCaseImpl(
            AppointmentRepository appointmentRepository,
            CancelAppointmentUseCase cancelAppointmentUseCase,
            AppointmentSchedulingService appointmentSchedulingService,
            PatientResolutionStrategy patientByIdResolutionStrategy,
            AppointmentChangeEvents changeEvents) {
        this.appointmentRepository = appointmentRepository;
        this.cancelAppointmentUseCase = cancelAppointmentUseCase;
        this.appointmentSchedulingService = appointmentSchedulingService;
        this.patientByIdResolutionStrategy = patientByIdResolutionStrategy;
        this.changeEvents = changeEvents;
    }

    @Override
    @Transactional
    public Appointment reschedule(
            UUID appointmentId,
            UUID requesterDoctorId,
            UUID newDoctorId,
            SpecialtyCode specialty,
            LocalDate date,
            AppointmentTime startTime,
            UUID performedBy) {

        // Bloquea la original: el paciente de la cita nueva sale de aquí, nunca del request
        Appointment original = appointmentRepository.findByIdForUpdate(appointmentId);

        if (requesterDoctorId != null && !requesterDoctorId.equals(original.getIdDoctor())) {
            throw new AppointmentAccessDeniedException(
                    "El médico solo puede reagendar sus propias citas");
        }

        try {
            cancelAppointmentUseCase.cancelForReschedule(appointmentId);
        } catch (AppointmentStateConflictException ex) {
            throw new CancelAppointmentNotAllowedException(
                    "Solo se pueden reagendar citas AGENDADAS. Estado actual: " + original.getAppointmentState());
        }

        Appointment replacement = appointmentSchedulingService.scheduleManual(
                PatientSchedulingContext.autonomous(original.getIdPatient()),
                newDoctorId,
                specialty,
                date,
                startTime,
                performedBy,
                patientByIdResolutionStrategy);

        // Último paso: un solo aviso de reagendamiento (la cancelación interna no publica el suyo)
        changeEvents.rescheduled(original, replacement);
        return replacement;
    }
}
