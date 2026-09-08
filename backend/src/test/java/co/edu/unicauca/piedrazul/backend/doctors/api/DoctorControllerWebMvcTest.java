package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.output.DoctorAvailableResponse;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.shared.enums.Workday;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DoctorControllerWebMvcTest {

    @Mock
    private DoctorService doctorService;

    @Mock
    private PersonExternalService personExternalService;

    @Mock
    private AppointmentExternalService appointmentExternalService;

    @Mock
    SecurityContextExtractor securityContextExtractor;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DoctorController(
                        doctorService,
                        personExternalService,
                        securityContextExtractor,
                        appointmentExternalService))
                .setControllerAdvice(new DoctorExceptionHandler())
                .build();
    }

    @Test
    void getAllDoctorsShouldReturnDoctorList() throws Exception {
        UUID doctorId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Doctor doctor = buildDoctor(doctorId, SpecialtyCode.QUIROPRAXIA);

        when(doctorService.findAllDoctors()).thenReturn(List.of(doctor));
        when(personExternalService.getPersonNames(List.of(doctorId))).thenReturn(Map.of(doctorId, "Dr. Gomez"));

        mockMvc.perform(get("/api/doctor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(doctorId.toString()))
                .andExpect(jsonPath("$[0].name").value("Dr. Gomez"))
                .andExpect(jsonPath("$[0].specialties[0]").value("QUIROPRAXIA"));
    }

    @Test
    void getSpecialtiesWithActiveDoctorsShouldReturnAvailableDates() throws Exception {
        UUID doctorId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        LocalDate availableDate = LocalDate.of(2026, 9, 15);
        DoctorAvailableResponse response = new DoctorAvailableResponse(
                List.of("QUIROPRAXIA"),
                doctorId,
                "Dr. Gomez",
                List.of(availableDate));

        when(doctorService.getSpecialtiesWithActiveDoctors(null,
                co.edu.unicauca.piedrazul.backend.doctors.api.SchedulingOrigin.MANUAL))
                .thenReturn(List.of(response));

        mockMvc.perform(get("/api/doctor/specialties-with-active-doctors")
                .param("schedulingOrigin", "MANUAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(doctorId.toString()))
                .andExpect(jsonPath("$[0].availableDates[0]").value("2026-09-15"));
    }

    @Test
    void getAvailableSlotsShouldDelegateToAppointmentModule() throws Exception {
        UUID doctorId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        LocalDate date = LocalDate.of(2026, 9, 15);
        when(appointmentExternalService.getAvailableSlots(doctorId, date))
                .thenReturn(List.of(LocalTime.of(8, 0), LocalTime.of(8, 30)));

        mockMvc.perform(get("/api/doctor/{doctorId}/available-slots", doctorId)
                .param("date", "2026-09-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("08:00:00"))
                .andExpect(jsonPath("$[1]").value("08:30:00"));

        verify(appointmentExternalService).getAvailableSlots(doctorId, date);
    }

    // @Test
    // void updateDoctorAppointmentIntervalShouldReturnNoContent() throws Exception
    // {
    // UUID doctorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    //
    // mockMvc.perform(put("/api/doctor/{doctorId}/appointment-interval", doctorId)
    // .param("appointmentInterval", "30"))
    // .andExpect(status().isNoContent());
    //
    // verify(doctorService).updateDoctorAppointmentInterval(doctorId, 30);
    // }

    private Doctor buildDoctor(UUID doctorId, SpecialtyCode specialtyCode) {
        Doctor doctor = new Doctor(doctorId, LocalDate.now().minusDays(10), LocalDate.now().plusDays(10), 4, true, 20);

        Specialty specialty = new Specialty();
        specialty.setCode(specialtyCode);
        specialty.setName(specialtyCode.name());

        doctor.addSpecialty(specialty);
        doctor.updateSchedule(Workday.LUNES, LocalTime.of(8, 0), LocalTime.of(12, 0));

        return doctor;
    }
}