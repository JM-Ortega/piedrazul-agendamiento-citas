package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.DocumentType;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.Gender;
import co.edu.unicauca.piedrazul.backend.appointment.domain.model.SchedulingOrigin;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.DoctorConfigConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.PatientConsultPort;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.input.AppointmentRequest;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.doctors.config.DoctorDataInitializer;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientDataInitializer;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.support.PostgresIntegrationSupport;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.application.KeycloakUserService;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Primera IT HTTP+seguridad del proyecto: a diferencia del resto (que usan {@code @DataJpaTest}
 * + wiring manual), un controlador con {@code @PreAuthorize} necesita el filtro de seguridad y
 * el AOP de method-security reales, así que aquí se usa {@code @SpringBootTest} (contexto
 * completo) + {@code @AutoConfigureMockMvc}. Cobertura representativa, no exhaustiva de los 13
 * endpoints x roles — ver plan.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AppointmentControllerIT.TestSecurityBeans.class)
class AppointmentControllerIT extends PostgresIntegrationSupport {

    @TestConfiguration
    static class TestSecurityBeans {
        /**
         * Evita que el arranque intente resolver el issuer real de Keycloak
         * (spring.security.oauth2.resourceserver.jwt.issuer-uri, no disponible en este entorno
         * de test). Nunca se invoca de verdad: las peticiones autenticadas usan el
         * post-processor jwt() de spring-security-test, que inyecta la Authentication
         * directamente sin pasar por el JwtDecoder real.
         */
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("No se decodifica JWT real en este test");
            };
        }
    }

    @MockitoBean
    private DoctorConfigConsultPort doctorConfigConsultPort;
    @MockitoBean
    private PatientConsultPort patientConsultPort;
    @MockitoBean
    private KeycloakUserService keycloakUserService;
    @MockitoBean
    private VerificationCodeSender verificationCodeSender;
    // Estos ApplicationRunner siembran datos vía Keycloak real al arrancar la app
    // (solo si sus tablas están vacías, que es justamente el caso en Postgres de test);
    // se mockean para que el arranque no intente una conexión HTTP real a Keycloak.
    @MockitoBean
    private DoctorDataInitializer doctorDataInitializer;
    @MockitoBean
    private PatientDataInitializer patientDataInitializer;

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    @Autowired
    private AppointmentJpaRepository appointmentJpaRepository;
    @Autowired
    private PersonExternalService personExternalService;
    @Autowired
    private DoctorRepository doctorRepository;

    private final AtomicLong documentSequence = new AtomicLong(System.nanoTime());

    private String uniqueDocument() {
        return String.valueOf(1_000_000_000L + (Math.abs(documentSequence.incrementAndGet()) % 900_000_000L));
    }

    private UUID newDoctor() {
        PersonSummary person = personExternalService.createPerson(
                IdentificationType.CEDULA, uniqueDocument(), "Doc", "Tor", "3000000001", null, null);
        doctorRepository.save(new Doctor(person.id(), LocalDate.now(), LocalDate.now().plusYears(1), 4, true, 30));
        return person.id();
    }

    private JwtRequestPostProcessor jwtWithRole(UUID subject, String role) {
        return jwt()
                .jwt(builder -> builder
                        .subject(subject.toString())
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void shouldReturnUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/appointments/list-all-states"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("no autorizado"));
    }

    @Test
    void shouldAllowAdminToUpdateAutonomousSchedulingConfig() throws Exception {
        mockMvc.perform(put("/api/appointments/config/autonomous-scheduling")
                        .param("enabled", "true")
                        .with(jwtWithRole(UUID.randomUUID(), "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void shouldForbidNonAdminFromUpdatingAutonomousSchedulingConfig() throws Exception {
        mockMvc.perform(put("/api/appointments/config/autonomous-scheduling")
                        .param("enabled", "true")
                        .with(jwtWithRole(UUID.randomUUID(), "PATIENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldListAllAppointmentStatesForSchedulerRole() throws Exception {
        mockMvc.perform(get("/api/appointments/list-all-states")
                        .with(jwtWithRole(UUID.randomUUID(), "SCHEDULER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(AppointmentState.values().length));
    }

    @Test
    void shouldResolveDoctorFromJwtSubjectForDailyAgenda() throws Exception {
        UUID subject = UUID.randomUUID();
        UUID doctorId = newDoctor();
        when(doctorConfigConsultPort.findByUserId(subject)).thenReturn(Optional.of(doctorId));

        mockMvc.perform(get("/api/appointments/doctor-daily-agenda")
                        .param("date", LocalDate.now().toString())
                        .with(jwtWithRole(subject, "DOCTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void shouldPersistAppointmentWhenSchedulerSchedulesManualAppointment() throws Exception {
        UUID doctorId = newDoctor();
        when(doctorConfigConsultPort.getIntervalMinutesByDoctor(doctorId)).thenReturn(30);
        when(doctorConfigConsultPort.getDoctorName(doctorId)).thenReturn("Dra. Prueba");

        AppointmentRequest request = new AppointmentRequest();
        request.setDoctorId(doctorId);
        request.setSpecialty(SpecialtyCode.TERAPIA_NEURAL);
        request.setDate(LocalDate.now().plusDays(1));
        request.setStartTime(LocalTime.of(9, 0));
        request.setSchedulingOrigin(SchedulingOrigin.MANUAL);
        request.setDocumentType(DocumentType.CEDULA);
        request.setDocumentNumber(uniqueDocument());
        request.setFirstName("Ana");
        request.setLastName("Ruiz");
        request.setPhone("3001234567");
        request.setGender(Gender.FEMENINO);
        request.setBirthDate(LocalDate.of(1990, 6, 15));

        mockMvc.perform(post("/api/appointments")
                        .with(jwtWithRole(UUID.randomUUID(), "SCHEDULER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        assertThat(appointmentJpaRepository.findByIdDoctorAndDate(doctorId, request.getDate())).hasSize(1);
    }
}
