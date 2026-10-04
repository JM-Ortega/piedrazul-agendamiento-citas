package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientUpdateService;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Los catálogos que usa el formulario de registro de pacientes. */
class PatientCatalogEndpointsTest {

    private PatientService patientService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        patientService = mock(PatientService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PatientController(
                        patientService,
                        mock(PatientUpdateService.class),
                        mock(AppointmentExternalService.class),
                        mock(SecurityContextExtractor.class),
                        mock(PersonExternalService.class)))
                .build();
    }

    @Test
    void theGenderTypesAreListedInTheOrderOfTheCatalog() throws Exception {
        when(patientService.getAllGenderTypes()).thenReturn(Arrays.asList(PatientSex.values()));

        mvc.perform(get("/api/patients/gender-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0]").value("MASCULINO"))
                .andExpect(jsonPath("$[1]").value("FEMENINO"))
                .andExpect(jsonPath("$[2]").value("OTRO"));
    }
}
