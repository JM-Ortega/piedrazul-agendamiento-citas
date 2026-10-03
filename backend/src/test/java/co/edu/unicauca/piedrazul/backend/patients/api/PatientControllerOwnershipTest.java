package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.config.security.JwtAuthConverter;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientResponse;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientSummaryResponse;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientUpdateService;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Un paciente solo llega a sus propios datos (IDOR). El rol le da acceso al endpoint, no a
 * cualquier paciente: el controlador debe atarlo a la persona de su token.
 *
 * <p>Se usa un contexto con {@code @EnableMethodSecurity}, así un arreglo que quite el rol
 * PATIENT del {@code @PreAuthorize} también cuenta como correcto.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PatientControllerOwnershipTest.Config.class)
class PatientControllerOwnershipTest {

    private static final UUID OWN_USER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OWN_PERSON_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("22222222-0000-0000-0000-000000000002");

    private static final PatientData OWN = patient(OWN_PERSON_ID, OWN_USER_ID, "1001001", "Ana", "Ruiz");
    private static final PatientData OTHER = patient(OTHER_PERSON_ID, null, "1002002", "Luis", "Mora");

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        PatientService patientService() {
            return mock(PatientService.class);
        }

        @Bean
        PersonExternalService personExternalService() {
            return mock(PersonExternalService.class);
        }

        @Bean
        PatientController patientController(PatientService patientService, PersonExternalService personExternalService) {
            return new PatientController(
                    patientService,
                    mock(PatientUpdateService.class),
                    mock(AppointmentExternalService.class),
                    new SecurityContextExtractor(),
                    personExternalService);
        }
    }

    @Autowired
    private PatientController controller;

    @Autowired
    private PatientService patientService;

    @Autowired
    private PersonExternalService personExternalService;

    @BeforeEach
    void setUp() {
        reset(patientService, personExternalService);
        when(personExternalService.findPersonIdByUserId(OWN_USER_ID)).thenReturn(OWN_PERSON_ID);
        when(patientService.findByUserId(OWN_USER_ID)).thenReturn(Optional.of(OWN));
        when(patientService.findById(OWN_PERSON_ID)).thenReturn(Optional.of(OWN));
        when(patientService.findById(OTHER_PERSON_ID)).thenReturn(Optional.of(OTHER));
        when(patientService.findByDocumentNumber(OWN.identification())).thenReturn(Optional.of(OWN));
        when(patientService.findByDocumentNumber(OTHER.identification())).thenReturn(Optional.of(OTHER));
        when(patientService.searchByDocumentNumberPrefix("100")).thenReturn(List.of(OWN, OTHER));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- consulta por documento ----

    /** Es una consulta del personal: el paciente usa GET /api/patients/me. */
    @Test
    void aPatientCannotLookUpPatientsByDocument() {
        authenticateAs(OWN_USER_ID, "PATIENT");

        assertThatThrownBy(() -> controller.findByDocument(OTHER.identification()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.findByDocument(OWN.identification()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void staffCanLookUpAnyPatientByDocument() {
        authenticateAs(UUID.randomUUID(), "SCHEDULER");

        PatientResponse response = controller.findByDocument(OTHER.identification());

        assertThat(response.getId()).isEqualTo(OTHER_PERSON_ID);
    }

    // ---- búsqueda por prefijo de documento ----

    @Test
    void aPatientCannotDiscoverOtherPatientsByDocumentPrefix() {
        authenticateAs(OWN_USER_ID, "PATIENT");

        Optional<List<PatientSummaryResponse>> response = callIgnoringRejection(
                () -> controller.searchByDocumentPrefix("100"));

        response.ifPresent(found -> assertThat(found)
                .as("un paciente buscó por prefijo y encontró a otros pacientes")
                .extracting(PatientSummaryResponse::getId)
                .doesNotContain(OTHER_PERSON_ID));
    }

    @Test
    void staffCanSearchPatientsByDocumentPrefix() {
        authenticateAs(UUID.randomUUID(), "SCHEDULER");

        List<PatientSummaryResponse> found = controller.searchByDocumentPrefix("100");

        assertThat(found).extracting(PatientSummaryResponse::getId)
                .containsExactlyInAnyOrder(OWN_PERSON_ID, OTHER_PERSON_ID);
    }

    // ---- consulta por id ----

    @Test
    void aPatientCannotReadAnotherPatientById() {
        authenticateAs(OWN_USER_ID, "PATIENT");

        Optional<PatientResponse> response = callIgnoringRejection(() -> controller.findById(OTHER_PERSON_ID));

        assertThat(response).as("un paciente leyó a otro por su id").isEmpty();
    }

    // ---- utilidades ----

    /** Autentica como lo haría la cadena real: un JWT de Keycloak pasado por {@link JwtAuthConverter}. */
    private static void authenticateAs(UUID userId, String role) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(userId.toString())
                .claim("preferred_username", "user-" + role.toLowerCase())
                .claim("realm_access", Map.of("roles", List.of(role)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthConverter().convert(jwt));
    }

    /**
     * El resultado de la llamada, o vacío si fue rechazada. Rechazar (403 o 404) es una respuesta
     * válida ante un paciente ajeno; lo que no es válido es devolver sus datos.
     */
    private static <T> Optional<T> callIgnoringRejection(Supplier<T> call) {
        try {
            return Optional.ofNullable(call.get());
        } catch (AccessDeniedException | PatientNotFoundException rejected) {
            return Optional.empty();
        }
    }

    private static PatientData patient(UUID personId, UUID userId, String document, String firstName, String lastName) {
        return new PatientData(personId, userId, IdentificationType.CEDULA, document, firstName, lastName,
                "3000000000", firstName.toLowerCase() + "@correo.com", null, null, null);
    }
}
