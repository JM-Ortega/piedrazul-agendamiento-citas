package co.edu.unicauca.piedrazul.backend.appointment.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.input.*;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.mappers.CitaDtoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;


import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class AppointmentControllerIntegrationTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;

    // ↓ @MockitoBean reemplaza @MockBean en Spring Boot 4.x
    @MockitoBean
    ScheduleManualAppointmentUseCase scheduleManualUseCase;
    
    @MockitoBean
    ScheduleAutonomousAppointmentUseCase scheduleAutonomousUseCase;
    
    @MockitoBean
    ListAppointmentsUseCase listAppointmentsUseCase;
    
    @MockitoBean
    ListMyAppointmentsUseCase listMyAppointmentsUseCase;
    
    @MockitoBean
    GetAvailableSlotsUseCase getAvailableSlotsUseCase;
    
    @MockitoBean
    GetSpecialtiesWithDoctorUseCase getSpecialtiesWithDoctorUseCase;
    
    @MockitoBean
    UpdateAppointmentStatusUseCase updateAppointmentStatusUseCase;
    
    @MockitoBean
    CitaDtoMapper citaDtoMapper;

    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private RequestPostProcessor jwtForRole(String role) {
        return jwt().jwt(token -> token
                        .claim("preferred_username", "test.user")
                        .claim("realm_access", java.util.Map.of("roles", java.util.List.of(role))))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void deberiaRetornar201AlAgendarCitaManualConOrigenManual() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .with(jwtForRole("SCHEDULER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                                "doctorId": "00000000-0000-0000-0000-000000000001",
                                "specialty": "FISIOTERAPIA",
                                "date": "2026-06-15",
                                "startTime": "09:00:00",
                                "schedulingOrigin": "MANUAL",
                                "documentType": "CEDULA",
                                "documentNumber": "12345678",
                                "firstName": "Carlos",
                                "lastName": "Lopez",
                                "phone": "3001234567",
                                "gender": "MASCULINO",
                                "birthDate": "1990-05-15"
                            }
                        """))
                .andExpect(status().isCreated());
    }

    @Test
    void deberiaRetornar200AlListarCitas() throws Exception {
        when(listAppointmentsUseCase.listBy(any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/appointments")
                        .with(jwtForRole("SCHEDULER")))
                .andExpect(status().isOk());
    }

    @Test
    void deberiaRetornar200AlMarcarCitaComoAtendida() throws Exception {
        UUID appointmentId = UUID.randomUUID();

        mockMvc.perform(put("/api/appointments/{id}/mark-as-attended", appointmentId)
                        .with(jwtForRole("DOCTOR")))
                .andExpect(status().isOk());
    }

    @Test
    void deberiaRetornar200AlMarcarCitaComoNoAsistida() throws Exception {
        UUID appointmentId = UUID.randomUUID();

        mockMvc.perform(put("/api/appointments/{id}/mark-as-unassisted", appointmentId)
                        .with(jwtForRole("DOCTOR")))
                .andExpect(status().isOk());
    }
}