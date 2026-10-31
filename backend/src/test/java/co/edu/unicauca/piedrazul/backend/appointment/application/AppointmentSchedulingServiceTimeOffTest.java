package co.edu.unicauca.piedrazul.backend.appointment.application;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentConfigRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.AppointmentRepository;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.IsNewPatientUseCase;
import co.edu.unicauca.piedrazul.backend.appointment.exception.DoctorOnTimeOffException;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AppointmentSchedulingServiceTimeOffTest {

    private final AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);
    private final DoctorConfigConsultPort doctorPort = mock(DoctorConfigConsultPort.class);

    private final AppointmentSchedulingService service = new AppointmentSchedulingService(
            appointmentRepository,
            doctorPort,
            mock(AppointmentService.class),
            mock(ApplicationEventPublisher.class),
            mock(IsNewPatientUseCase.class),
            mock(SecurityContextExtractor.class),
            mock(AppointmentConfigRepository.class));

    @Test
    void scheduleManualShouldRejectDateInsideDoctorTimeOff() {
        UUID doctorId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusWeeks(30);
        when(doctorPort.isOnTimeOff(doctorId, date)).thenReturn(true);

        assertThatThrownBy(() -> service.scheduleManual(
                null, doctorId, SpecialtyCode.TERAPIA_NEURAL, date, null, UUID.randomUUID(), null))
                .isInstanceOf(DoctorOnTimeOffException.class);

        verify(appointmentRepository, never()).save(any());
    }
}
