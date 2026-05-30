package co.edu.unicauca.piedrazul.backend.appointment.component;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.entity.AppointmentEntity;
import co.edu.unicauca.piedrazul.backend.appointment.util.AppointmentTestFactory;
import co.edu.unicauca.piedrazul.backend.doctors.DoctorExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorResponse;
import co.edu.unicauca.piedrazul.backend.patients.PatientModuleApi;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 👇 NUEVOS IMPORTS ESTÁTICOS
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;  // ← Agrega esta línea
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc  // ← Agrega esta línea
@ActiveProfiles("test")
class AppointmentComponentTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;  // ← Ahora sí funcionará

    @Autowired
    private AppointmentJpaRepository appointmentJpaRepository;

    @MockitoBean
    private DoctorExternalService doctorExternalService;

    @MockitoBean
    private PatientService patientService;

    @BeforeEach
    void setUp() {
        appointmentJpaRepository.deleteAll();

        when(doctorExternalService.getDoctorName(AppointmentTestFactory.DOCTOR_ID))
                .thenReturn("Dr. Juan Perez");
        when(doctorExternalService.getIntervalMinutesByDoctor(AppointmentTestFactory.DOCTOR_ID))
                .thenReturn(30);


        PatientData patientData = buildPatientData(AppointmentTestFactory.PATIENT_ID, USER_ID);
        when(patientService.findById(AppointmentTestFactory.PATIENT_ID))
                .thenReturn(Optional.of(patientData));
        when(patientService.findByDocumentNumber("12345678"))
                .thenReturn(Optional.of(patientData));
        when(patientService.findByUserId(USER_ID))
                .thenReturn(Optional.of(patientData));


    }

    @Test
    void deberiaAgendarCitaManualYPersistir() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .with(jwtForRole("SCHEDULER", USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "doctorId": "%s",
                                "specialty": "FISIOTERAPIA",
                                "date": "%s",
                                "startTime": "%s",
                                "schedulingOrigin": "MANUAL",
                                "documentType": "CEDULA",
                                "documentNumber": "12345678",
                                "firstName": "Carlos",
                                "lastName": "Lopez",
                                "phone": "3001234567",
                                "gender": "MASCULINO",
                                "birthDate": "1990-05-15",
                                "email": "carlos@email.com"
                            }
                            """.formatted(
                                AppointmentTestFactory.DOCTOR_ID,
                                AppointmentTestFactory.FUTURE_DATE,
                                AppointmentTestFactory.START_TIME
                        )))
                .andExpect(status().isCreated());

        assertThat(appointmentJpaRepository.findAll()).hasSize(1);
    }

    @Test
    void deberiaAgendarCitaAutonomaYListarMisCitas() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .with(jwtForRole("PATIENT", USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "doctorId": "%s",
                                "specialty": "FISIOTERAPIA",
                                "date": "%s",
                                "startTime": "%s",
                                "schedulingOrigin": "AUTONOMO",
                                "patientId": "%s"
                            }
                            """.formatted(
                                AppointmentTestFactory.DOCTOR_ID,
                                AppointmentTestFactory.FUTURE_DATE,
                                AppointmentTestFactory.START_TIME,
                                AppointmentTestFactory.PATIENT_ID
                        )))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/appointments/me")
                        .with(jwtForRole("PATIENT", USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void deberiaListarCitasPorDoctor() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .with(jwtForRole("SCHEDULER", USER_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "doctorId": "%s",
                                "specialty": "FISIOTERAPIA",
                                "date": "%s",
                                "startTime": "%s",
                                "schedulingOrigin": "MANUAL",
                                "documentType": "CEDULA",
                                "documentNumber": "12345678",
                                "firstName": "Carlos",
                                "lastName": "Lopez",
                                "phone": "3001234567",
                                "gender": "MASCULINO",
                                "birthDate": "1990-05-15"
                            }
                            """.formatted(
                                AppointmentTestFactory.DOCTOR_ID,
                                AppointmentTestFactory.FUTURE_DATE,
                                AppointmentTestFactory.START_TIME
                        )))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/appointments")
                        .with(jwtForRole("SCHEDULER", USER_ID))
                        .param("idDoctor", AppointmentTestFactory.DOCTOR_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].doctorName").value("Dr. Juan Perez"));
    }

    @Test
    void deberiaObtenerFranjasDisponibles() throws Exception {
        when(doctorExternalService.getSlotsByDoctor(any(), any()))
                .thenReturn(List.of(LocalTime.of(9, 0), LocalTime.of(9, 30)));
        when(doctorExternalService.getIntervalMinutesByDoctor(any()))
                .thenReturn(30);

        mockMvc.perform(get("/api/appointments/available-slots")
                        .with(jwtForRole("SCHEDULER", USER_ID))
                        .param("doctorId", AppointmentTestFactory.DOCTOR_ID.toString())
                        .param("date", AppointmentTestFactory.FUTURE_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].time").value("09:00:00"));
    }

    @Test
    void deberiaRetornarEspecialidadesConDoctor() throws Exception {
        when(doctorExternalService.getActiveDoctorIds())
                .thenReturn(List.of(AppointmentTestFactory.DOCTOR_ID));
        when(doctorExternalService.getDoctorInfoByIds(anyList()))
                .thenReturn(List.of(new DoctorResponse(
                        "FISIOTERAPIA",
                        AppointmentTestFactory.DOCTOR_ID,
                        "Dr. Juan Perez",
                        LocalDate.now().plusMonths(6),
                        List.of(1, 3, 5)
                )));
        when(doctorExternalService.getIntervalMinutesByDoctor(any()))
                .thenReturn(30);
        when(doctorExternalService.getSlotsByDoctor(any(), any()))
                .thenReturn(List.of(LocalTime.of(9, 0), LocalTime.of(9, 30)));

        mockMvc.perform(get("/api/appointments/specialties-with-doctor")
                        .with(jwtForRole("SCHEDULER", USER_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].specialty").value("FISIOTERAPIA"));
    }

    @Test
    void deberiaActualizarEstadoDeCita() throws Exception {
        AppointmentEntity entity = AppointmentTestFactory.buildEntity();
        AppointmentEntity saved = appointmentJpaRepository.save(entity);

        mockMvc.perform(put("/api/appointments/{id}/mark-as-attended", saved.getIdAppointment())
                        .with(jwtForRole("DOCTOR", USER_ID)))
                .andExpect(status().isOk());

        AppointmentEntity updated = appointmentJpaRepository.findById(saved.getIdAppointment()).orElseThrow();
        assertThat(updated.getAppointmentState()).isEqualTo(AppointmentState.ATENDIDA);
    }

    private RequestPostProcessor jwtForRole(String role, UUID userId) {
        return jwt().jwt(token -> token
                        .subject(userId.toString())
                        .claim("preferred_username", "test.user")
                        .claim("realm_access", java.util.Map.of("roles", List.of(role))))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private PatientData buildPatientData(UUID patientId, UUID userId) {
        return new PatientData(
                patientId,
                userId,
                PatientDocumentType.CEDULA,
                "12345678",
                "Carlos",
                "Lopez",
                "3001234567",
                "carlos@email.com",
                PatientGender.MASCULINO,
                LocalDate.of(1990, 5, 15),
                null
        );
    }
}

