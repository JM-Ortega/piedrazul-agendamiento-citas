package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.NoAvailableDoctorsException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.SlotTimeService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetSpecialtiesWithDoctorUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private DoctorConfigConsultPort doctorConfigConsultPort;

    @Mock
    private SlotTimeService slotTimeService;

    @Mock
    private IsNewPatientUseCase isNewPatientUseCase;

    private GetSpecialtiesWithDoctorUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetSpecialtiesWithDoctorUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort,
                slotTimeService,
                isNewPatientUseCase
        );
    }

    // ─────────────────────────────────────────────
    // Sin médicos activos
    // ─────────────────────────────────────────────

    @Test
    void getSpecialtiesWithDoctorShouldThrowWhenNoActiveDoctors() {
                when(isNewPatientUseCase.isNewPatient(anyPatient())).thenReturn(false);
                when(doctorConfigConsultPort.getActiveDoctorIds()).thenReturn(List.of());

                assertThatThrownBy(() -> useCase.getSpecialtiesWithDoctor(anyPatient()))
                .isInstanceOf(NoAvailableDoctorsException.class)
                .hasMessageContaining("No hay medicos activos");
    }

    // ─────────────────────────────────────────────
    // Médicos activos pero sin slots disponibles
    // ─────────────────────────────────────────────

    @Test
    void getSpecialtiesWithDoctorShouldThrowWhenActiveDoctorsHaveNoSlots() {
        UUID idDoctor = UUID.randomUUID();
        when(isNewPatientUseCase.isNewPatient(anyPatient())).thenReturn(false);
        when(doctorConfigConsultPort.getActiveDoctorIds()).thenReturn(List.of(idDoctor));
        when(doctorConfigConsultPort.getBookingWindowWeeksByDoctorIds(List.of(idDoctor)))
                .thenReturn(Map.of(idDoctor, 1));
        when(doctorConfigConsultPort.getIntervalMinutesByDoctorIds(List.of(idDoctor)))
                .thenReturn(Map.of(idDoctor, 30));

        // Sin slots configurados para ningún día del periodo
        when(doctorConfigConsultPort.getSlotsByDoctor(eq(idDoctor), any(LocalDate.class)))
                .thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndDate(eq(idDoctor), any(LocalDate.class)))
                .thenReturn(List.of());
        when(slotTimeService.calculateAvailable(any(), any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> useCase.getSpecialtiesWithDoctor(anyPatient()))
                .isInstanceOf(NoAvailableDoctorsException.class)
                .hasMessageContaining("No hay medicos con espacios disponibles");
    }

    // ─────────────────────────────────────────────
    // Flujo feliz — un doctor con slots disponibles
    // ─────────────────────────────────────────────

    @Test
    void getSpecialtiesWithDoctorShouldReturnDoctorWhenHasAvailableSlots() {
        UUID idDoctor = UUID.randomUUID();
        DoctorResponse doctorInfo = new DoctorResponse(
                List.of("FISIOTERAPIA"),
                idDoctor,
                "Dr. Lopez",
                LocalDate.now().plusMonths(6),
                LocalDate.now(),
                List.of(1, 2, 3)
        );

        when(isNewPatientUseCase.isNewPatient(anyPatient())).thenReturn(false);
        when(doctorConfigConsultPort.getActiveDoctorIds()).thenReturn(List.of(idDoctor));
        when(doctorConfigConsultPort.getBookingWindowWeeksByDoctorIds(List.of(idDoctor)))
                .thenReturn(Map.of(idDoctor, 1));
        when(doctorConfigConsultPort.getIntervalMinutesByDoctorIds(List.of(idDoctor)))
                .thenReturn(Map.of(idDoctor, 30));
        when(doctorConfigConsultPort.getSlotsByDoctor(eq(idDoctor), any(LocalDate.class)))
                .thenReturn(List.of(new AppointmentTime(LocalTime.of(9, 0))));
        when(appointmentRepository.findByDoctorIdAndDate(eq(idDoctor), any(LocalDate.class)))
                .thenReturn(List.of());
        when(slotTimeService.calculateAvailable(any(), any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(doctorConfigConsultPort.getDoctorInfoByIds(List.of(idDoctor)))
                .thenReturn(List.of(doctorInfo));

        List<DoctorResponse> result = useCase.getSpecialtiesWithDoctor(anyPatient());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(idDoctor);
        assertThat(result.getFirst().specialty()).containsExactly("FISIOTERAPIA");
    }

    @Test
    void getSpecialtiesWithDoctorShouldSelectDoctorWithMoreSlotsFirst() {
        UUID idDoctor1 = UUID.randomUUID(); // pocos slots
        UUID idDoctor2 = UUID.randomUUID(); // muchos slots

        when(isNewPatientUseCase.isNewPatient(anyPatient())).thenReturn(false);
        when(doctorConfigConsultPort.getActiveDoctorIds())
                .thenReturn(List.of(idDoctor1, idDoctor2));
        when(doctorConfigConsultPort.getBookingWindowWeeksByDoctorIds(List.of(idDoctor1, idDoctor2)))
                .thenReturn(Map.of(idDoctor1, 1, idDoctor2, 1));
        when(doctorConfigConsultPort.getIntervalMinutesByDoctorIds(List.of(idDoctor1, idDoctor2)))
                .thenReturn(Map.of(idDoctor1, 30, idDoctor2, 30));

        // Doctor 1 — 1 slot disponible
        when(doctorConfigConsultPort.getSlotsByDoctor(eq(idDoctor1), any(LocalDate.class)))
                .thenReturn(List.of(new AppointmentTime(LocalTime.of(9, 0))));
        when(appointmentRepository.findByDoctorIdAndDate(eq(idDoctor1), any(LocalDate.class)))
                .thenReturn(List.of());

        // Doctor 2 — 3 slots disponibles
        when(doctorConfigConsultPort.getSlotsByDoctor(eq(idDoctor2), any(LocalDate.class)))
                .thenReturn(List.of(
                        new AppointmentTime(LocalTime.of(8, 0)),
                        new AppointmentTime(LocalTime.of(8, 30)),
                        new AppointmentTime(LocalTime.of(9, 0))
                ));
        when(appointmentRepository.findByDoctorIdAndDate(eq(idDoctor2), any(LocalDate.class)))
                .thenReturn(List.of());
        when(slotTimeService.calculateAvailable(any(), any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DoctorResponse response1 = new DoctorResponse(
                List.of("FISIOTERAPIA"),
                idDoctor1,
                "Dr. Lopez",
                LocalDate.now().plusMonths(6),
                LocalDate.now(),
                List.of(1)
        );
        DoctorResponse response2 = new DoctorResponse(
                List.of("QUIROPRAXIA"),
                idDoctor2,
                "Dr. Gomez",
                LocalDate.now().plusMonths(6),
                LocalDate.now(),
                List.of(1, 2)
        );

        // El usecase ordena por slots desc antes de pedir info
        // idDoctor2 tiene más slots → va primero en la lista
        when(doctorConfigConsultPort.getDoctorInfoByIds(List.of(idDoctor2, idDoctor1)))
                .thenReturn(List.of(response2, response1));

        List<DoctorResponse> result = useCase.getSpecialtiesWithDoctor(anyPatient());

        // El primero debe ser el que tiene más slots
        assertThat(result.getFirst().id()).isEqualTo(idDoctor2);
    }

    @Test
    void getSpecialtiesWithDoctorShouldNotRepeatSpecialty() {
        UUID idDoctor1 = UUID.randomUUID();
        UUID idDoctor2 = UUID.randomUUID();

        when(isNewPatientUseCase.isNewPatient(anyPatient())).thenReturn(false);
        when(doctorConfigConsultPort.getActiveDoctorIds())
                .thenReturn(List.of(idDoctor1, idDoctor2));
        when(doctorConfigConsultPort.getBookingWindowWeeksByDoctorIds(List.of(idDoctor1, idDoctor2)))
                .thenReturn(Map.of(idDoctor1, 1, idDoctor2, 1));
        when(doctorConfigConsultPort.getIntervalMinutesByDoctorIds(List.of(idDoctor1, idDoctor2)))
                .thenReturn(Map.of(idDoctor1, 30, idDoctor2, 30));

        // Ambos tienen slots
        when(doctorConfigConsultPort.getSlotsByDoctor(any(UUID.class), any(LocalDate.class)))
                .thenReturn(List.of(new AppointmentTime(LocalTime.of(9, 0))));
        when(appointmentRepository.findByDoctorIdAndDate(any(UUID.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(slotTimeService.calculateAvailable(any(), any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Ambos tienen la misma especialidad
        DoctorResponse response1 = new DoctorResponse(
                List.of("FISIOTERAPIA"),
                idDoctor1,
                "Dr. Lopez",
                LocalDate.now().plusMonths(6),
                LocalDate.now(),
                List.of(1)
        );
        DoctorResponse response2 = new DoctorResponse(
                List.of("FISIOTERAPIA"),
                idDoctor2,
                "Dr. Gomez",
                LocalDate.now().plusMonths(6),
                LocalDate.now(),
                List.of(1)
        );

        when(doctorConfigConsultPort.getDoctorInfoByIds(any()))
                .thenReturn(List.of(response1, response2));

        List<DoctorResponse> result = useCase.getSpecialtiesWithDoctor(anyPatient());

        // Solo debe aparecer una vez FISIOTERAPIA
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().specialty()).containsExactly("FISIOTERAPIA");
    }

    @Test
    void getSpecialtiesWithDoctorShouldUseOnlyGeneralDoctorsForNewPatient() {
        UUID generalDoctorId = UUID.randomUUID();

        when(isNewPatientUseCase.isNewPatient(anyPatient())).thenReturn(true);
        when(doctorConfigConsultPort.getActiveGeneralDoctorIds()).thenReturn(List.of(generalDoctorId));
        when(doctorConfigConsultPort.getBookingWindowWeeksByDoctorIds(List.of(generalDoctorId)))
                .thenReturn(Map.of(generalDoctorId, 1));
        when(doctorConfigConsultPort.getIntervalMinutesByDoctorIds(List.of(generalDoctorId)))
                .thenReturn(Map.of(generalDoctorId, 30));
        when(doctorConfigConsultPort.getSlotsByDoctor(eq(generalDoctorId), any(LocalDate.class)))
                .thenReturn(List.of(new AppointmentTime(LocalTime.of(8, 0))));
        when(appointmentRepository.findByDoctorIdAndDate(eq(generalDoctorId), any(LocalDate.class)))
                .thenReturn(List.of());
        when(slotTimeService.calculateAvailable(any(), any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DoctorResponse doctorInfo = new DoctorResponse(
                List.of("MEDICINA_GENERAL"),
                generalDoctorId,
                "Dr. General",
                LocalDate.now().plusMonths(6),
                LocalDate.now(),
                List.of(1, 2, 3)
        );

        when(doctorConfigConsultPort.getDoctorInfoByIds(List.of(generalDoctorId)))
                .thenReturn(List.of(doctorInfo));

        List<DoctorResponse> result = useCase.getSpecialtiesWithDoctor(anyPatient());

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(generalDoctorId);
        assertThat(result.getFirst().specialty()).containsExactly("MEDICINA_GENERAL");
    }

    private UUID anyPatient() {
        return UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    }

}