package co.edu.unicauca.piedrazul.backend.doctors.api;

import co.edu.unicauca.piedrazul.backend.doctors.application.DoctorService;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Doctor;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Schedule;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.shared.enums.SpecialtyCode;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Workday;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DoctorController(doctorService, personExternalService))
                .setControllerAdvice(new DoctorExceptionHandler())
                .build();
    }

    @Test
    void getAllDoctorsShouldReturnDoctorList() throws Exception {
        UUID doctorId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        Doctor doctor = buildDoctor(doctorId, SpecialtyCode.QUIROPRAXIA);

        when(doctorService.findAllDoctors()).thenReturn(List.of(doctor));
        when(personExternalService.getPersonNames(List.of(doctorId))).thenReturn(Map.of(doctorId, "Dr. Gomez"));

        mockMvc.perform(get("/api/doctor/doctors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(doctorId.toString()))
                .andExpect(jsonPath("$[0].name").value("Dr. Gomez"))
                .andExpect(jsonPath("$[0].specialties[0]").value("QUIROPRAXIA"));
    }

    @Test
    void updateDoctorAppointmentIntervalShouldReturnNoContent() throws Exception {
        UUID doctorId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        mockMvc.perform(put("/api/doctor/doctors/{doctorId}/appointment-interval", doctorId)
                        .param("appointmentInterval", "30"))
                .andExpect(status().isNoContent());

        verify(doctorService).updateDoctorAppointmentInterval(doctorId, 30);
    }

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