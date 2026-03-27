package co.edu.unicauca.piedrazul.backend.appointment.aplication;

import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.NoAvailableDoctorsException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorPublicInfo;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetSpecialtiesWithDoctorUseCaseImplTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private DoctorConfigConsultPort doctorConfigConsultPort;

    private GetSpecialtiesWithDoctorUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetSpecialtiesWithDoctorUseCaseImpl(
                appointmentRepository,
                doctorConfigConsultPort
        );
    }

    // ─────────────────────────────────────────────
    // Sin médicos activos
    // ─────────────────────────────────────────────

    @Test
    void getSpecialtiesWithDoctorShouldThrowWhenNoActiveDoctors() {
        when(doctorConfigConsultPort.getActiveDoctorIds()).thenReturn(List.of());

        assertThatThrownBy(() -> useCase.getSpecialtiesWithDoctor())
                .isInstanceOf(NoAvailableDoctorsException.class)
                .hasMessageContaining("No hay medicos activos");
    }

    // ─────────────────────────────────────────────
    // Médicos activos pero sin slots disponibles
    // ─────────────────────────────────────────────

    @Test
    void getSpecialtiesWithDoctorShouldThrowWhenActiveDoctorsHaveNoSlots() {
        UUID idDoctor = UUID.randomUUID();
        when(doctorConfigConsultPort.getActiveDoctorIds()).thenReturn(List.of(idDoctor));

        // Sin slots configurados para ningún día del periodo
        when(doctorConfigConsultPort.getSlotsByDoctor(eq(idDoctor), any(LocalDate.class)))
                .thenReturn(List.of());
        when(appointmentRepository.findByDoctorIdAndDate(eq(idDoctor), any(LocalDate.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> useCase.getSpecialtiesWithDoctor())
                .isInstanceOf(NoAvailableDoctorsException.class)
                .hasMessageContaining("No hay medicos con espacios disponibles");
    }

    // ─────────────────────────────────────────────
    // Flujo feliz — un doctor con slots disponibles
    // ─────────────────────────────────────────────

    @Test
    void getSpecialtiesWithDoctorShouldReturnDoctorWhenHasAvailableSlots() {
        UUID idDoctor = UUID.randomUUID();
        DoctorPublicInfo   doctorInfo = new DoctorPublicInfo(
                "FISIOTERAPIA", idDoctor, "Dr. Lopez",
                LocalDate.now().plusMonths(6), List.of(1, 2, 3)
        );

        when(doctorConfigConsultPort.getActiveDoctorIds()).thenReturn(List.of(idDoctor));
        // Tiene slots disponibles al menos un día laborable
        when(doctorConfigConsultPort.getSlotsByDoctor(eq(idDoctor), any(LocalDate.class)))
                .thenReturn(List.of(new AppointmentTime(LocalTime.of(9, 0))));
        when(appointmentRepository.findByDoctorIdAndDate(eq(idDoctor), any(LocalDate.class)))
                .thenReturn(List.of());
        when(doctorConfigConsultPort.getDoctorInfoByIds(List.of(idDoctor)))
                .thenReturn(List.of(doctorInfo));

        List<DoctorPublicInfo> result = useCase.getSpecialtiesWithDoctor();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(idDoctor);
        assertThat(result.getFirst().specialty()).isEqualTo("FISIOTERAPIA");
    }

    @Test
    void getSpecialtiesWithDoctorShouldSelectDoctorWithMoreSlotsFirst() {
        UUID idDoctor1 = UUID.randomUUID(); // pocos slots
        UUID idDoctor2 = UUID.randomUUID(); // muchos slots

        when(doctorConfigConsultPort.getActiveDoctorIds())
                .thenReturn(List.of(idDoctor1, idDoctor2));

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

        DoctorPublicInfo response1 = new DoctorPublicInfo(
                "FISIOTERAPIA", idDoctor1, "Dr. Lopez",
                LocalDate.now().plusMonths(6), List.of(1)
        );
        DoctorPublicInfo response2 = new DoctorPublicInfo(
                "QUIROPRAXIA", idDoctor2, "Dr. Gomez",
                LocalDate.now().plusMonths(6), List.of(1, 2)
        );

        // El usecase ordena por slots desc antes de pedir info
        // idDoctor2 tiene más slots → va primero en la lista
        when(doctorConfigConsultPort.getDoctorInfoByIds(List.of(idDoctor2, idDoctor1)))
                .thenReturn(List.of(response2, response1));

        List<DoctorPublicInfo> result = useCase.getSpecialtiesWithDoctor();

        // El primero debe ser el que tiene más slots
        assertThat(result.getFirst().id()).isEqualTo(idDoctor2);
    }

    @Test
    void getSpecialtiesWithDoctorShouldNotRepeatSpecialty() {
        UUID idDoctor1 = UUID.randomUUID();
        UUID idDoctor2 = UUID.randomUUID();

        when(doctorConfigConsultPort.getActiveDoctorIds())
                .thenReturn(List.of(idDoctor1, idDoctor2));

        // Ambos tienen slots
        when(doctorConfigConsultPort.getSlotsByDoctor(any(UUID.class), any(LocalDate.class)))
                .thenReturn(List.of(new AppointmentTime(LocalTime.of(9, 0))));
        when(appointmentRepository.findByDoctorIdAndDate(any(UUID.class), any(LocalDate.class)))
                .thenReturn(List.of());

        // Ambos tienen la misma especialidad
        DoctorPublicInfo response1 = new DoctorPublicInfo(
                "FISIOTERAPIA", idDoctor1, "Dr. Lopez",
                LocalDate.now().plusMonths(6), List.of(1)
        );
        DoctorPublicInfo response2 = new DoctorPublicInfo(
                "FISIOTERAPIA", idDoctor2, "Dr. Gomez",
                LocalDate.now().plusMonths(6), List.of(1)
        );

        when(doctorConfigConsultPort.getDoctorInfoByIds(any()))
                .thenReturn(List.of(response1, response2));

        List<DoctorPublicInfo> result = useCase.getSpecialtiesWithDoctor();

        // Solo debe aparecer una vez FISIOTERAPIA
        assertThat(result).hasSize(1);
        assertThat(result.stream().map(DoctorPublicInfo::specialty).distinct().count())
                .isEqualTo(1);
    }

}