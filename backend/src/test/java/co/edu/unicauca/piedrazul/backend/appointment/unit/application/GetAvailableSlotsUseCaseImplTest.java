package co.edu.unicauca.piedrazul.backend.appointment.unit.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Appointment;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Specialty;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.appointment.application.GetAvailableSlotsUseCaseImpl;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetAvailableSlotsUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private DoctorConfigConsultPort doctorConfigConsultPort;

    @Mock
        private SlotTimeService slotTimeService;

    private GetAvailableSlotsUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetAvailableSlotsUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                slotTimeService
        );
    }

    // ─────────────────────────────────────────────
    // Flujo feliz
    // ─────────────────────────────────────────────

    @Test
    void getAvailableSlotsShouldReturnSlotsFromDomainService() {
        UUID idDoctor = UUID.randomUUID();
        LocalDate date = LocalDate.now();

        List<AppointmentTime> doctorSlots = List.of(
                new AppointmentTime(LocalTime.of(8, 0)),
                new AppointmentTime(LocalTime.of(8, 30)),
                new AppointmentTime(LocalTime.of(9, 0))
        );
        List<Appointment> existingAppointments = List.of();
        List<AppointmentTime> availableSlots = List.of(
                new AppointmentTime(LocalTime.of(8, 30)),
                new AppointmentTime(LocalTime.of(9, 0))
        );

        when(doctorConfigConsultPort.getSlotsByDoctor(idDoctor, date)).thenReturn(doctorSlots);
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(existingAppointments);
        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(30);
        when(slotTimeService.calculateAvailable(doctorSlots, existingAppointments, 30))
                .thenReturn(availableSlots);

        List<AppointmentTime> result = useCase.getAvailableSlots(idDoctor, date);

        assertThat(result).isEqualTo(availableSlots);
    }

    @Test
    void getAvailableSlotsShouldReturnEmptyWhenAllSlotsOccupied() {
        UUID idDoctor = UUID.randomUUID();
        LocalDate date = LocalDate.now();

        List<AppointmentTime> doctorSlots = List.of(
                new AppointmentTime(LocalTime.of(8, 0))
        );
        List<Appointment> existingAppointments = List.of(
                buildAppointment(idDoctor, new AppointmentTime(LocalTime.of(8, 0)), date)
        );

        when(doctorConfigConsultPort.getSlotsByDoctor(idDoctor, date)).thenReturn(doctorSlots);
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(existingAppointments);
        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(30);
        when(slotTimeService.calculateAvailable(doctorSlots, existingAppointments, 30))
                .thenReturn(List.of());

        List<AppointmentTime> result = useCase.getAvailableSlots(idDoctor, date);

        assertThat(result).isEmpty();
    }

    @Test
    void getAvailableSlotsShouldReturnAllSlotsWhenNoneOccupied() {
        UUID idDoctor = UUID.randomUUID();
        LocalDate date = LocalDate.now();

        List<AppointmentTime> doctorSlots = List.of(
                new AppointmentTime(LocalTime.of(8, 0)),
                new AppointmentTime(LocalTime.of(8, 30))
        );

        when(doctorConfigConsultPort.getSlotsByDoctor(idDoctor, date)).thenReturn(doctorSlots);
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(30);
        when(slotTimeService.calculateAvailable(doctorSlots, List.of(), 30))
                .thenReturn(doctorSlots);

        List<AppointmentTime> result = useCase.getAvailableSlots(idDoctor, date);

        assertThat(result).hasSize(2);
    }

    // ─────────────────────────────────────────────
    // Verificación de orquestación
    // ─────────────────────────────────────────────

    @Test
    void getAvailableSlotsShouldCallCollaboratorsInCorrectOrder() {
        UUID idDoctor = UUID.randomUUID();
        LocalDate date = LocalDate.now();

        when(doctorConfigConsultPort.getSlotsByDoctor(idDoctor, date)).thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndDate(idDoctor, date)).thenReturn(List.of());
        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(idDoctor)).thenReturn(30);
        when(slotTimeService.calculateAvailable(List.of(), List.of(), 30)).thenReturn(List.of());

        useCase.getAvailableSlots(idDoctor, date);

        verify(doctorConfigConsultPort).getSlotsByDoctor(idDoctor, date);
        verify(appointmentRepository).findByDoctorIdAndDate(idDoctor, date);
        verify(doctorConfigConsultPort).getIntervalMinutesByDoctor(idDoctor);
        verify(slotTimeService).calculateAvailable(List.of(), List.of(), 30);
    }

    // ─────────────────────────────────────────────
    // Fixture
    // ─────────────────────────────────────────────

    private Appointment buildAppointment(UUID idDoctor, AppointmentTime time, LocalDate date) {
        return Appointment.reconstruct(
                UUID.randomUUID(), idDoctor, "Dr. Lopez",
                UUID.randomUUID(), "Carlos Gomez",
                PatientInfo.of(
                        DocumentType.CEDULA, "12345678", "Carlos", "Gomez",
                        "3001234567", Gender.MASCULINO,
                        LocalDate.of(1990, 6, 15), null, null
                ),
                Specialty.FISIOTERAPIA, AppointmentState.AGENDADA,
                date, time, SchedulingOrigin.AUTONOMO
        );
    }
}