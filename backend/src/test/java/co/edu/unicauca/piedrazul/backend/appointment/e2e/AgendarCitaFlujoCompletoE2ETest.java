package co.edu.unicauca.piedrazul.backend.appointment.e2e;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.AppointmentState;
import co.edu.unicauca.piedrazul.backend.appointment.infrastructure.persistence.AppointmentJpaRepository;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.DoctorRepository;
import co.edu.unicauca.piedrazul.backend.doctors.infrastructure.persistence.ScheduleRepository;
import co.edu.unicauca.piedrazul.backend.patients.domain.*;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * E2E simple con H2 en memoria y contexto completo de Spring Boot.
 * - MockMvc simula HTTP dentro del mismo proceso (controladores, filtros, validaciones, seguridad).
 * - La autenticacion se simula con jwt() para poblar @AuthenticationPrincipal Jwt.
 * - La persistencia y casos de uso son reales (sin mocks de repositorios/servicios).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgendarCitaFlujoCompletoE2ETest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String USER_ID_STRING = "11111111-1111-1111-1111-111111111111";

    @Autowired MockMvc mockMvc;
    @Autowired DoctorRepository doctorRepository;
    @Autowired ScheduleRepository scheduleRepository;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    @Autowired PatientRepository patientRepository;
    @Autowired AppointmentJpaRepository appointmentJpaRepository;

    private UUID doctorId;
    private UUID patientId;
    private LocalDate appointmentDate;

    @BeforeEach
    void setUp() {

        appointmentJpaRepository.deleteAll();
        scheduleRepository.deleteAll();
        doctorRepository.deleteAll();
        patientRepository.deleteAll();

        appointmentDate = nextWorkingDay(LocalDate.now().plusDays(1));

        // Crear doctor
        Doctor doctor = new Doctor();
        doctor.setFirstName("Juan");
        doctor.setLastName("Perez");
        doctor.setIdentification("123456789");
        doctor.setDocumentType(co.edu.unicauca.piedrazul.backend.doctors.domain.DocumentType.CEDULA);
        doctor.setPhone("3001234567");
        doctor.setSpecialty(List.of(Specialty.FISIOTERAPIA));
        doctor.setStatus(true);
        doctor.setLaborStart(appointmentDate.minusDays(1));
        doctor.setLaborEnd(appointmentDate.plusMonths(6));
        doctor.setAppointmentInterval(30);
        doctor = doctorRepository.save(doctor);
        doctorId = doctor.getIdDoctor();

        Schedule schedule = new Schedule(
                doctor,
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                toWorkday(appointmentDate.getDayOfWeek())
        );
        scheduleRepository.save(schedule);

        // Crear paciente
        Patient patient = new Patient(
                DocumentType.CEDULA,
                "12345678",
                "Carlos",
                "Lopez",
                "3001234567",
                "carlos@email.com",
                Gender.MASCULINO,
                LocalDate.of(1990, 5, 15),
                null,
                USER_ID
        );
        patient = patientRepository.save(patient);
        patientId = patient.getId();
    }

    @Test
    void pacienteAutenticadoAgendaCitaAutonomaExitosamente() throws Exception {

        // 1. Consultar slots disponibles
        MvcResult slotsResult = mockMvc.perform(get("/api/appointments/available-slots")
                        .param("doctorId", doctorId.toString())
                        .param("date", appointmentDate.toString())
                        .with(jwtPatient()))
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> slots = objectMapper.readValue(
                slotsResult.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
        );
        assertThat(slots).isNotEmpty();
        String startTime = slots.getFirst().get("time").toString();

        // 2. Agendar la cita
        Map<String, Object> requestBody = Map.of(
                "doctorId", doctorId.toString(),
                "specialty", "FISIOTERAPIA",
                "date", appointmentDate.toString(),
                "startTime", startTime,
                "schedulingOrigin", "AUTONOMO",
                "patientId", patientId.toString()
        );

        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody))
                        .with(jwtPatient()))
                .andExpect(status().isCreated());

        // 3. Consultar mis citas
        MvcResult myAppointmentsResult = mockMvc.perform(get("/api/appointments/me")
                        .with(jwtPatient()))
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> myAppointments = objectMapper.readValue(
                myAppointmentsResult.getResponse().getContentAsString(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
        );

        // 4. Verificar que la cita aparece con estado AGENDADA
        assertThat(myAppointments).hasSize(1);
        assertThat(myAppointments.getFirst().get("appointmentState"))
                .isEqualTo(AppointmentState.AGENDADA.name());
    }

    private static LocalDate nextWorkingDay(LocalDate date) {
        LocalDate current = date;
        while (current.getDayOfWeek() == DayOfWeek.SATURDAY || current.getDayOfWeek() == DayOfWeek.SUNDAY) {
            current = current.plusDays(1);
        }
        return current;
    }

    private static Workday toWorkday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> Workday.LUNES;
            case TUESDAY -> Workday.MARTES;
            case WEDNESDAY -> Workday.MIERCOLES;
            case THURSDAY -> Workday.JUEVES;
            case FRIDAY -> Workday.VIERNES;
            default -> throw new IllegalArgumentException("Dia no laboral: " + dayOfWeek);
        };
    }

    private static RequestPostProcessor jwtPatient() {
        return jwt()
                .jwt(jwt -> jwt
                        .subject(USER_ID_STRING)
                        .claim("preferred_username", USER_ID_STRING)
                        .claim("realm_access", Map.of("roles", List.of("PATIENT"))))
                .authorities(new SimpleGrantedAuthority("ROLE_PATIENT"));
    }
}
