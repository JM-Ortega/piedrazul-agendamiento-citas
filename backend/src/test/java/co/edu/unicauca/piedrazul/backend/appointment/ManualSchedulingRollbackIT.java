package co.edu.unicauca.piedrazul.backend.appointment;

import co.edu.unicauca.piedrazul.backend.appointment.application.AppointmentSchedulingService;
import co.edu.unicauca.piedrazul.backend.appointment.application.IsNewPatientUseCaseImpl;
import co.edu.unicauca.piedrazul.backend.appointment.application.scheduling.ManualPatientResolutionStrategy;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentTime;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.*;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.AppointmentService;
import co.edu.unicauca.piedrazul.backend.appointment.domain.service.BusySlotService;
import co.edu.unicauca.piedrazul.backend.appointment.exception.FirstAppointmentMustBeGeneralMedicineException;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal.PatientSchedulingContext;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration.PatientConsultPortImpl;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration.PatientProvisioningPortImpl;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.AppointmentMapper;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentRepositoryImpl;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientLinkFinalizer;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.user.application.PersonExternalServiceImp;
import co.edu.unicauca.piedrazul.backend.user.infrastructure.persistence.PersonRepository;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationAttemptProcessor;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationService;
import co.edu.unicauca.piedrazul.backend.verification.infrastructure.persistence.JpaVerificationCodeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Un fallo después de resolver el paciente debe revertir {@code Person},
 * {@code Patient} y la cita.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        // Módulo de citas
        AppointmentMapper.class,
        PatientConsultPortImpl.class,
        PatientProvisioningPortImpl.class,
        ManualSchedulingRollbackIT.SchedulingBeans.class,
        // Módulo de pacientes y sus colaboradores reales
        PatientService.class,
        PatientLinkFinalizer.class,
        PersonExternalServiceImp.class,
        VerificationService.class,
        VerificationAttemptProcessor.class,
        JpaVerificationCodeStore.class
})
// Sin transacción del test: la debe abrir scheduleManual.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ManualSchedulingRollbackIT extends PostgresIntegrationSupport {

    private static final LocalDate BIRTH_DATE = LocalDate.now().minusYears(30);

    @TestConfiguration
    static class SchedulingBeans {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }

        @Bean
        AppointmentRepository appointmentRepository(
                AppointmentJpaRepository jpaRepository, AppointmentMapper mapper) {
            return new AppointmentRepositoryImpl(jpaRepository, mapper);
        }

        @Bean
        BusySlotService busySlotService() {
            return new BusySlotService();
        }

        @Bean
        AppointmentService appointmentService(BusySlotService busySlotService) {
            return new AppointmentService(busySlotService);
        }

        @Bean
        IsNewPatientUseCaseImpl isNewPatientUseCase(
                AppointmentRepository appointmentRepository, PatientConsultPort patientConsultPort) {
            return new IsNewPatientUseCaseImpl(appointmentRepository, patientConsultPort);
        }

        @Bean
        ManualPatientResolutionStrategy manualPatientResolutionStrategy(
                PatientProvisioningPort patientProvisioningPort) {
            return new ManualPatientResolutionStrategy(patientProvisioningPort);
        }

        @Bean
        AppointmentSchedulingService appointmentSchedulingService(
                AppointmentRepository appointmentRepository,
                DoctorConfigConsultPort doctorConfigConsultPort,
                AppointmentService appointmentService,
                ApplicationEventPublisher eventPublisher,
                IsNewPatientUseCaseImpl isNewPatientUseCase,
                SecurityContextExtractor securityExtractor,
                AppointmentConfigRepository appointmentConfigRepository) {
            return new AppointmentSchedulingService(
                    appointmentRepository, doctorConfigConsultPort,
                    appointmentService, eventPublisher, isNewPatientUseCase,
                    securityExtractor, appointmentConfigRepository);
        }
    }

    // Fronteras ajenas a la propiedad bajo prueba. KeycloakUserService implementa
    // UserModuleApi, así que este mock cubre ambos usos.
    @MockitoBean
    private DoctorConfigConsultPort doctorConfigConsultPort;
    @MockitoBean
    private KeycloakUserService keycloakUserService;
    @MockitoBean
    private UserAccountProvisioningApi userAccountProvisioningApi;
    @MockitoBean
    private VerificationCodeSender verificationCodeSender;

    @Autowired
    private AppointmentSchedulingService schedulingService;
    @Autowired
    private ManualPatientResolutionStrategy manualStrategy;
    @Autowired
    private PersonExternalService personExternalService;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private AppointmentJpaRepository appointmentJpaRepository;

    private String document;
    private final UUID doctorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        document = String.valueOf(System.nanoTime()).substring(0, 10);

        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(any())).thenReturn(30);
        when(doctorConfigConsultPort.getDoctorName(any())).thenReturn("Dra. Prueba");
    }

    private PatientSchedulingContext manualContext() {
        return PatientSchedulingContext.manual(
                DocumentType.CEDULA, document, "Ana", "Ruiz", "3001234567",
                Gender.FEMENINO, BIRTH_DATE, "ana@example.com", null);
    }

    private void scheduleWith(SpecialtyCode specialty) {
        schedulingService.scheduleManual(
                manualContext(), doctorId, specialty,
                LocalDate.now().plusDays(1), new AppointmentTime(LocalTime.of(9, 0)),
                UUID.randomUUID(), manualStrategy);
    }

    @Test
    void shouldRollBackPersonAndPatientWhenSchedulingFailsAfterResolvingThePatient() {
        assertTrue(personExternalService.findByIdentification(document).isEmpty(),
                "precondición: el documento no debe existir todavía");

        // Patient no expone el documento; se compara el conteo antes y después.
        long patientCountBefore = patientRepository.count();

        assertThrows(FirstAppointmentMustBeGeneralMedicineException.class,
                () -> scheduleWith(SpecialtyCode.FISIOTERAPIA));

        assertTrue(personRepository.findByIdentification(document).isEmpty(),
                "la persona creada durante el agendamiento debe revertirse");
        assertEquals(patientCountBefore, patientRepository.count(),
                "el paciente creado durante el agendamiento debe revertirse");
        assertTrue(appointmentJpaRepository.findAll().stream()
                        .noneMatch(appointment -> doctorId.equals(appointment.getIdDoctor())),
                "no debe quedar ninguna cita");
    }
}
