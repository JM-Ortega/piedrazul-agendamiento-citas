package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.PatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.CancelAppointmentUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentAccessDeniedException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.AppointmentStateConflictException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.CancelAppointmentNotAllowedException;
import co.edu.unicauca.piedrazul.backend.appointment.exception.SlotNotAvailableException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reagendar = cancelar + agendar (manual) sin reglas propias. La atomicidad (rollback real) se
 * prueba en {@code RescheduleAppointmentIT}.
 */
@ExtendWith(MockitoExtension.class)
class RescheduleAppointmentUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private CancelAppointmentUseCase cancelAppointmentUseCase;
    @Mock
    private AppointmentSchedulingService appointmentSchedulingService;
    @Mock
    private PatientResolutionStrategy patientByIdStrategy;

    private RescheduleAppointmentUseCaseImpl useCase;

    private final UUID appointmentId = UUID.randomUUID();
    private final UUID originalDoctorId = UUID.randomUUID();
    private final UUID newDoctorId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID performedBy = UUID.randomUUID();
    private final LocalDate newDate = LocalDate.now().plusDays(3);
    private final AppointmentTime newTime = new AppointmentTime(LocalTime.of(10, 0));

    private Appointment original;

    @BeforeEach
    void setUp() {
        useCase = new RescheduleAppointmentUseCaseImpl(
                appointmentRepository, cancelAppointmentUseCase, appointmentSchedulingService, patientByIdStrategy);
        original = Appointment.reconstruct(appointmentId, originalDoctorId, patientId, SpecialtyCode.TERAPIA_NEURAL,
                AppointmentState.AGENDADA, LocalDate.now().plusDays(1), new AppointmentTime(LocalTime.of(9, 0)),
                SchedulingOrigin.AUTONOMO);
    }

    private Appointment reschedule(UUID requesterDoctorId) {
        return useCase.reschedule(appointmentId, requesterDoctorId, newDoctorId, SpecialtyCode.FISIOTERAPIA,
                newDate, newTime, performedBy);
    }

    @Test
    void shouldCancelTheOriginalAndThenScheduleManuallyForTheSamePatient() {
        Appointment created = Appointment.reconstruct(UUID.randomUUID(), newDoctorId, patientId,
                SpecialtyCode.FISIOTERAPIA, AppointmentState.AGENDADA, newDate, newTime, SchedulingOrigin.MANUAL);
        when(appointmentRepository.findByIdForUpdate(appointmentId)).thenReturn(original);
        when(appointmentSchedulingService.scheduleManual(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(created);

        Appointment result = reschedule(null);

        assertThat(result).isSameAs(created);
        InOrder order = inOrder(appointmentRepository, cancelAppointmentUseCase, appointmentSchedulingService);
        order.verify(appointmentRepository).findByIdForUpdate(appointmentId);
        // Personal de la clínica: sin chequeo de propiedad de paciente
        order.verify(cancelAppointmentUseCase).cancel(appointmentId, null);
        ArgumentCaptor<PatientSchedulingContext> context = ArgumentCaptor.forClass(PatientSchedulingContext.class);
        order.verify(appointmentSchedulingService).scheduleManual(context.capture(), eq(newDoctorId),
                eq(SpecialtyCode.FISIOTERAPIA), eq(newDate), eq(newTime), eq(performedBy), eq(patientByIdStrategy));
        // El paciente sale de la cita original
        assertThat(context.getValue().idPatient()).isEqualTo(patientId);
    }

    @Test
    void shouldNeverUseTheAutonomousFlow() {
        when(appointmentRepository.findByIdForUpdate(appointmentId)).thenReturn(original);

        reschedule(null);

        verify(appointmentSchedulingService, never())
                .scheduleAutonomous(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aDoctorCanRescheduleTheirOwnAppointment() {
        when(appointmentRepository.findByIdForUpdate(appointmentId)).thenReturn(original);

        reschedule(originalDoctorId);

        verify(cancelAppointmentUseCase).cancel(appointmentId, null);
        verify(appointmentSchedulingService).scheduleManual(any(), eq(newDoctorId), any(), any(), any(), any(), any());
    }

    @Test
    void aDoctorCannotRescheduleAnotherDoctorsAppointment() {
        when(appointmentRepository.findByIdForUpdate(appointmentId)).thenReturn(original);

        assertThatThrownBy(() -> reschedule(UUID.randomUUID()))
                .isInstanceOf(AppointmentAccessDeniedException.class);

        verifyNoInteractions(cancelAppointmentUseCase, appointmentSchedulingService);
    }

    @Test
    void shouldTranslateAStateConflictIntoCancelNotAllowedAndNotSchedule() {
        when(appointmentRepository.findByIdForUpdate(appointmentId)).thenReturn(original);
        doThrow(new AppointmentStateConflictException("Solo las citas AGENDADAS pueden cancelarse"))
                .when(cancelAppointmentUseCase).cancel(appointmentId, null);

        assertThatThrownBy(() -> reschedule(null))
                .isInstanceOf(CancelAppointmentNotAllowedException.class);

        verifyNoInteractions(appointmentSchedulingService);
    }

    @Test
    void shouldPropagateCancelNotAllowedForPastAppointmentsAndNotSchedule() {
        when(appointmentRepository.findByIdForUpdate(appointmentId)).thenReturn(original);
        doThrow(new CancelAppointmentNotAllowedException("No se puede cancelar una cita de una fecha pasada"))
                .when(cancelAppointmentUseCase).cancel(appointmentId, null);

        assertThatThrownBy(() -> reschedule(null))
                .isInstanceOf(CancelAppointmentNotAllowedException.class);

        verifyNoInteractions(appointmentSchedulingService);
    }

    @Test
    void shouldPropagateSchedulingFailures() {
        when(appointmentRepository.findByIdForUpdate(appointmentId)).thenReturn(original);
        when(appointmentSchedulingService.scheduleManual(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new SlotNotAvailableException("Slot ocupado"));

        assertThatThrownBy(() -> reschedule(null))
                .isInstanceOf(SlotNotAvailableException.class);
    }
}
