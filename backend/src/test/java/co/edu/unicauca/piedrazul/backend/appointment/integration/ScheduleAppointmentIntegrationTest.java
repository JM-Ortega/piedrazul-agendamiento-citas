// ScheduleAppointmentIntegrationTest.java
package co.edu.unicauca.piedrazul.backend.appointment.integration;

import co.edu.unicauca.piedrazul.backend.appointment.application.ScheduleManualAppointmentUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.domain.exception.PatientAlreadyScheduledInSpecialtyException;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.BusySlotService;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.*;
import co.edu.unicauca.piedrazul.backend.appointment.util.AppointmentTestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@ActiveProfiles("test")
@Import({AppointmentRepositoryImpl.class, AppointmentMapper.class})
class ScheduleAppointmentIntegrationTest {

    @Autowired
    private AppointmentJpaRepository jpaRepository;

    @Autowired
    private AppointmentRepositoryImpl appointmentRepository;

    @MockitoBean
    private PatientConsultPort patientConsultPort;

    // Mocks de puertos externos
    private DoctorConfigConsultPort doctorPort;
    private PatientConsultPort patientPort;

    private ScheduleManualAppointmentUseCaseImpl useCase;

    private final UUID doctorId = AppointmentTestFactory.DOCTOR_ID;
    private final UUID patientId = AppointmentTestFactory.PATIENT_ID;

    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();

        doctorPort = mock(DoctorConfigConsultPort.class);
        patientPort = patientConsultPort;

        // Configuración por defecto de los mocks
        when(doctorPort.getDoctorName(doctorId)).thenReturn("Dr. Juan Pérez");
        when(doctorPort.getIntervalMinutesByDoctor(doctorId)).thenReturn(30);
        when(patientPort.findByDocumentNumber(anyString()))
                .thenReturn(Optional.of(new PatientSnapshot(
                        patientId, AppointmentTestFactory.buildPatientInfo())));
        when(patientPort.findById(patientId))
                .thenReturn(AppointmentTestFactory.buildPatientInfo());

        BusySlotService busySlotService = new BusySlotService();
        AppointmentService appointmentService = new AppointmentService(busySlotService);

        useCase = new ScheduleManualAppointmentUseCaseImpl(
                appointmentRepository,
                doctorPort,
                appointmentService,
                patientPort
        );
    }

    @Test
    void deberiaAgendarCitaManualYPersistirla() {
        // Act
        Appointment result = useCase.scheduleManual(
                DocumentType.CEDULA, "12345678",
                "Carlos", "López",
                "3001234567", Gender.MASCULINO,
                LocalDate.of(1990, 5, 15),
                "carlos@email.com", null,
                doctorId, Specialty.FISIOTERAPIA,
                AppointmentTestFactory.FUTURE_DATE,
                new AppointmentTime(AppointmentTestFactory.START_TIME)
        );

        // Assert — verifica que se guardó en BD
        assertThat(result.getIdAppointment()).isNotNull();
        assertThat(result.getAppointmentState()).isEqualTo(AppointmentState.AGENDADA);
        assertThat(jpaRepository.findAll()).hasSize(1);
    }

    @Test
    void deberiaFallarSiPacienteYaTieneCitaEnMismaEspecialidad() {
        // Arrange — agendar primera cita
        useCase.scheduleManual(
                DocumentType.CEDULA, "12345678",
                "Carlos", "López",
                "3001234567", Gender.MASCULINO,
                LocalDate.of(1990, 5, 15),
                null, null,
                doctorId, Specialty.FISIOTERAPIA,
                AppointmentTestFactory.FUTURE_DATE,
                new AppointmentTime(AppointmentTestFactory.START_TIME)
        );

        // Act & Assert — segunda cita en la misma especialidad debe fallar
        assertThatThrownBy(() -> useCase.scheduleManual(
                DocumentType.CEDULA, "12345678",
                "Carlos", "López",
                "3001234567", Gender.MASCULINO,
                LocalDate.of(1990, 5, 15),
                null, null,
                doctorId, Specialty.FISIOTERAPIA,
                AppointmentTestFactory.FUTURE_DATE.plusDays(1),
                new AppointmentTime(AppointmentTestFactory.START_TIME)
        )).isInstanceOf(PatientAlreadyScheduledInSpecialtyException.class);
    }
}