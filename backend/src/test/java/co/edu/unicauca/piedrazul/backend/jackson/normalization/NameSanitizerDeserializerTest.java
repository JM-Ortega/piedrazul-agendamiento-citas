package co.edu.unicauca.piedrazul.backend.jackson.normalization;

import co.edu.unicauca.piedrazul.backend.doctors.api.dtos.input.CreateDoctorRequest;
import co.edu.unicauca.piedrazul.backend.doctors.domain.Specialty;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientDocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.PatientGender;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.CreatePatientWithUserRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameSanitizerDeserializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .findAndRegisterModules();

    @Test
    void shouldNormalizeWhitespaceAndCasing() throws Exception {
        String json = """
                {
                  "firstName": "   jUaN   pÉrEz   "
                }
                """;

        NormalizedNamePayload payload = objectMapper.readValue(json, NormalizedNamePayload.class);

        assertThat(payload.firstName()).isEqualTo("Juan Pérez");
    }

    @Test
    void shouldNormalizeNamesInsideCreateDoctorRequest() throws Exception {
        String json = """
                {
                  "firstName": "   jUaN   pErEz   ",
                  "lastName": "   dE   lA   cRuZ   ",
                  "documentType": "CEDULA",
                  "identification": "123456789",
                  "phone": "3001234567",
                  "specialty": ["FISIOTERAPIA"],
                  "laborStart": "2026-01-01",
                  "laborEnd": "2026-12-31",
                  "appointmentInterval": 30,
                  "schedules": [
                    {
                      "startTime": "07:00:00",
                      "endTime": "11:00:00",
                      "workday": "LUNES"
                    }
                  ],
                  "email": "juan.perez@example.com",
                  "password": "Doctor123!"
                }
                """;

        CreateDoctorRequest request = objectMapper.readValue(json, CreateDoctorRequest.class);

        assertThat(request.firstName()).isEqualTo("Juan Perez");
        assertThat(request.lastName()).isEqualTo("De La Cruz");
        assertThat(request.documentType().name()).isEqualTo("CEDULA");
        assertThat(request.specialty()).containsExactly(Specialty.FISIOTERAPIA);
        assertThat(request.schedules()).hasSize(1);
    }

      @Test
      void shouldNormalizeNamesInsideCreatePatientWithUserRequest() throws Exception {
        String json = """
            {
              "username": "juan.perez",
              "documentType": "CEDULA",
              "documentNumber": "123456789",
              "firstName": "   jUaN   cArLoS   ",
              "lastName": "   dE   lA   cRuZ   ",
              "phone": "3001234567",
              "email": "juan.perez@example.com",
              "gender": "MASCULINO",
              "birthDate": "2000-01-01",
              "guardianPhone": "3007654321",
              "password": "Doctor123!"
            }
            """;

        CreatePatientWithUserRequest request = objectMapper.readValue(json, CreatePatientWithUserRequest.class);

        assertThat(request.getFirstName()).isEqualTo("Juan Carlos");
        assertThat(request.getLastName()).isEqualTo("De La Cruz");
        assertThat(request.getDocumentType()).isEqualTo(PatientDocumentType.CEDULA);
        assertThat(request.getGender()).isEqualTo(PatientGender.MASCULINO);
        assertThat(request.getBirthDate()).isEqualTo(java.time.LocalDate.of(2000, 1, 1));
      }

    private record NormalizedNamePayload(@NormalizeName String firstName) {
    }
}