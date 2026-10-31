package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.output.AvailableDateSlots;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingDateSlots;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.internal.WorkingSchedule;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAvailableDatesAndSlotsUseCaseImplTest {

        @Mock
        private AppointmentRepository appointmentRepository;

        @Mock
        private DoctorConfigConsultPort doctorConfigConsultPort;

        @Mock
        private SlotTimeService slotTimeService;

        private GetAvailableDatesAndSlotsUseCaseImpl useCase;

        @BeforeEach
        void setUp() {
                useCase = new GetAvailableDatesAndSlotsUseCaseImpl(
                                appointmentRepository,
                                doctorConfigConsultPort,
                                slotTimeService);
        }

        // ─────────────────────────────────────────────
        // Flujo feliz
        // ─────────────────────────────────────────────

        @Test
        void getAvailableSlotsShouldReturnSlotsFromDomainService() {
                UUID idDoctor = UUID.randomUUID();
                LocalDate startDate = LocalDate.now().plusDays(1);
                LocalDate endDate = startDate.plusDays(1);

                List<WorkingDateSlots> workingDatesAndSlots = List.of(
                                new WorkingDateSlots(startDate, List.of(
                                                LocalTime.of(8, 0), LocalTime.of(8, 30))),
                                new WorkingDateSlots(endDate, List.of(LocalTime.of(9, 0))));
                WorkingSchedule workingSchedule = new WorkingSchedule(workingDatesAndSlots, 30);
                List<Appointment> existingAppointments = List.of();
                List<AvailableDateSlots> availableSlots = List.of(
                                new AvailableDateSlots(startDate, List.of(LocalTime.of(8, 30))),
                                new AvailableDateSlots(endDate, List.of(LocalTime.of(9, 0))));

                when(doctorConfigConsultPort.workingSchedule(idDoctor)).thenReturn(workingSchedule);
                when(appointmentRepository.findByDoctorAndDateBetween(idDoctor, startDate, endDate))
                                .thenReturn(existingAppointments);
                when(slotTimeService.calculateAvailable(workingDatesAndSlots, existingAppointments, 30))
                                .thenReturn(availableSlots);

                List<AvailableDateSlots> result = useCase.getAvailableDatesAndSlots(idDoctor);

                assertThat(result).isEqualTo(availableSlots);
        }

        @Test
        void getAvailableSlotsShouldReturnEmptyWhenAllSlotsOccupied() {
                UUID idDoctor = UUID.randomUUID();
                LocalDate date = LocalDate.now().plusDays(1);

                List<WorkingDateSlots> workingDatesAndSlots = List.of(
                                new WorkingDateSlots(date, List.of(LocalTime.of(8, 0))));
                WorkingSchedule workingSchedule = new WorkingSchedule(workingDatesAndSlots, 30);
                List<Appointment> existingAppointments = List.of(
                                buildAppointment(idDoctor, new AppointmentTime(LocalTime.of(8, 0)), date));

                when(doctorConfigConsultPort.workingSchedule(idDoctor)).thenReturn(workingSchedule);
                when(appointmentRepository.findByDoctorAndDateBetween(idDoctor, date, date))
                                .thenReturn(existingAppointments);
                when(slotTimeService.calculateAvailable(workingDatesAndSlots, existingAppointments, 30))
                                .thenReturn(List.of());

                List<AvailableDateSlots> result = useCase.getAvailableDatesAndSlots(idDoctor);

                assertThat(result).isEmpty();
        }

        @Test
        void getAvailableSlotsShouldReturnAllSlotsWhenNoneOccupied() {
                UUID idDoctor = UUID.randomUUID();
                LocalDate date = LocalDate.now().plusDays(1);

                List<WorkingDateSlots> workingDatesAndSlots = List.of(
                                new WorkingDateSlots(date, List.of(LocalTime.of(8, 0), LocalTime.of(8, 30))));
                WorkingSchedule workingSchedule = new WorkingSchedule(workingDatesAndSlots, 30);
                List<AvailableDateSlots> availableSlots = List.of(
                                new AvailableDateSlots(date, List.of(LocalTime.of(8, 0), LocalTime.of(8, 30))));

                when(doctorConfigConsultPort.workingSchedule(idDoctor)).thenReturn(workingSchedule);
                when(appointmentRepository.findByDoctorAndDateBetween(idDoctor, date, date)).thenReturn(List.of());
                when(slotTimeService.calculateAvailable(workingDatesAndSlots, List.of(), 30))
                                .thenReturn(availableSlots);

                List<AvailableDateSlots> result = useCase.getAvailableDatesAndSlots(idDoctor);

                assertThat(result).containsExactlyElementsOf(availableSlots);
        }

        // ─────────────────────────────────────────────
        // Verificación de orquestación
        // ─────────────────────────────────────────────

        @Test
        void getAvailableSlotsShouldCallCollaboratorsInCorrectOrder() {
                UUID idDoctor = UUID.randomUUID();
                LocalDate startDate = LocalDate.now().plusDays(1);
                LocalDate endDate = startDate.plusDays(1);
                List<WorkingDateSlots> workingDatesAndSlots = List.of(
                                new WorkingDateSlots(startDate, List.of(LocalTime.of(8, 0))),
                                new WorkingDateSlots(endDate, List.of(LocalTime.of(8, 30))));
                WorkingSchedule workingSchedule = new WorkingSchedule(workingDatesAndSlots, 30);

                when(doctorConfigConsultPort.workingSchedule(idDoctor)).thenReturn(workingSchedule);
                when(appointmentRepository.findByDoctorAndDateBetween(idDoctor, startDate, endDate))
                                .thenReturn(List.of());
                when(slotTimeService.calculateAvailable(workingDatesAndSlots, List.of(), 30)).thenReturn(List.of());

                useCase.getAvailableDatesAndSlots(idDoctor);

                var inOrder = inOrder(doctorConfigConsultPort, appointmentRepository, slotTimeService);
                inOrder.verify(doctorConfigConsultPort).workingSchedule(idDoctor);
                inOrder.verify(appointmentRepository).findByDoctorAndDateBetween(idDoctor, startDate, endDate);
                inOrder.verify(slotTimeService).calculateAvailable(workingDatesAndSlots, List.of(), 30);
        }

        @Test
        void getAvailableSlotsShouldReturnEmptyWithoutQueryingAppointmentsWhenScheduleIsEmpty() {
                UUID idDoctor = UUID.randomUUID();
                when(doctorConfigConsultPort.workingSchedule(idDoctor))
                                .thenReturn(new WorkingSchedule(List.of(), 30));

                List<AvailableDateSlots> result = useCase.getAvailableDatesAndSlots(idDoctor);

                assertThat(result).isEmpty();
                verify(doctorConfigConsultPort).workingSchedule(idDoctor);
                org.mockito.Mockito.verifyNoInteractions(appointmentRepository, slotTimeService);
        }

        @Test
        void getAvailableDatesShouldReturnOnlyDatesWithAvailableSlots() {
                UUID idDoctor = UUID.randomUUID();
                LocalDate firstDate = LocalDate.now().plusDays(1);
                LocalDate secondDate = firstDate.plusDays(1);
                List<WorkingDateSlots> workingDatesAndSlots = List.of(
                                new WorkingDateSlots(firstDate, List.of(LocalTime.of(8, 0))),
                                new WorkingDateSlots(secondDate, List.of(LocalTime.of(8, 30))));
                WorkingSchedule workingSchedule = new WorkingSchedule(workingDatesAndSlots, 30);
                List<Appointment> existingAppointments = List.of();

                when(doctorConfigConsultPort.workingSchedule(idDoctor)).thenReturn(workingSchedule);
                when(appointmentRepository.findByDoctorAndDateBetween(idDoctor, firstDate, secondDate))
                                .thenReturn(existingAppointments);
                when(slotTimeService.calculateAvailable(workingDatesAndSlots, existingAppointments, 30))
                                .thenReturn(List.of(new AvailableDateSlots(firstDate, List.of(LocalTime.of(8, 0)))));

                List<LocalDate> result = useCase.getAvailableDates(idDoctor);

                assertThat(result).containsExactly(firstDate);
        }

        @Test
        void getAvailableSlotsShouldCalculateOnlyTheRequestedDate() {
                UUID idDoctor = UUID.randomUUID();
                LocalDate requestedDate = LocalDate.now().plusDays(1);
                WorkingDateSlots requestedDateSlots = new WorkingDateSlots(
                                requestedDate,
                                List.of(LocalTime.of(8, 0), LocalTime.of(8, 30)));
                WorkingSchedule workingSchedule = new WorkingSchedule(
                                List.of(requestedDateSlots),
                                30);
                List<Appointment> existingAppointments = List.of();

                when(doctorConfigConsultPort.workingSchedule(idDoctor)).thenReturn(workingSchedule);
                when(appointmentRepository.findByDoctorAndDateBetween(
                                idDoctor, requestedDate, requestedDate)).thenReturn(existingAppointments);
                when(slotTimeService.calculateAvailable(
                                List.of(requestedDateSlots), existingAppointments, 30))
                                .thenReturn(List.of(new AvailableDateSlots(
                                                requestedDate,
                                                List.of(LocalTime.of(8, 30)))));

                List<LocalTime> result = useCase.getAvailableSlots(idDoctor, requestedDate);

                assertThat(result).containsExactly(LocalTime.of(8, 30));
        }

        @Test
        void getAvailableSlotsShouldReturnEmptyForDateOutsideWorkingSchedule() {
                UUID idDoctor = UUID.randomUUID();
                LocalDate configuredDate = LocalDate.now().plusDays(1);
                LocalDate requestedDate = configuredDate.plusDays(1);
                when(doctorConfigConsultPort.workingSchedule(idDoctor)).thenReturn(
                                new WorkingSchedule(List.of(new WorkingDateSlots(
                                                configuredDate, List.of(LocalTime.of(8, 0)))), 30));

                List<LocalTime> result = useCase.getAvailableSlots(idDoctor, requestedDate);

                assertThat(result).isEmpty();
                org.mockito.Mockito.verifyNoInteractions(appointmentRepository, slotTimeService);
        }

        // ─────────────────────────────────────────────
        // Fixture
        // ─────────────────────────────────────────────

        private Appointment buildAppointment(UUID idDoctor, AppointmentTime time, LocalDate date) {
                return Appointment.reconstruct(
                                UUID.randomUUID(),
                                idDoctor,
                                UUID.randomUUID(),
                                SpecialtyCode.FISIOTERAPIA, AppointmentState.AGENDADA,
                                date, time, SchedulingOrigin.AUTONOMO);
        }
}