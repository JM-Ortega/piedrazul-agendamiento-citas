package co.edu.unicauca.piedrazul.backend.patients.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientLinkFinalizer;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientUpdateService;
import co.edu.unicauca.piedrazul.backend.patients.infrastructure.persistence.PatientRepository;
import co.edu.unicauca.piedrazul.backend.shared.audit.SecurityContextExtractor;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import co.edu.unicauca.piedrazul.backend.shared.enums.Role;
import co.edu.unicauca.piedrazul.backend.user.PersonExternalService;
import co.edu.unicauca.piedrazul.backend.user.UserAccountProvisioningApi;
import co.edu.unicauca.piedrazul.backend.user.UserModuleApi;
import co.edu.unicauca.piedrazul.backend.user.api.dto.internal.PersonSummary;
import co.edu.unicauca.piedrazul.backend.user.exception.IdentityProviderException;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationAttemptProcessor;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeSender;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationCodeStore;
import co.edu.unicauca.piedrazul.backend.verification.application.VerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Contrato HTTP público de {@code POST /api/patients/link-user-account/request-code}, con los
 * servicios reales de pacientes y verificación y el manejador de errores real. Solo se simulan la
 * persistencia, el proveedor de identidad y el puerto de envío.
 */
class PatientRequestCodeContractTest {

    private static final String URL = "/api/patients/link-user-account/request-code";
    private static final String KNOWN_DOC = "1061234567";
    private static final String UNKNOWN_DOC = "9999999999";
    private static final String PHONE = "3001234567";
    private static final String EMAIL = "ana.ruiz@example.invalid";

    private static final String UNAVAILABLE_DETAIL =
            "El servicio de envío de códigos no está disponible temporalmente. Inténtalo más tarde.";
    private static final String CONTACT_DETAIL =
            "No es posible completar la solicitud con los datos de contacto registrados. "
                    + "Comunícate con la clínica para verificar o actualizar tu información.";

    private final ObjectMapper json = new ObjectMapper();
    private final UUID personId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private PatientRepository patientRepository;
    private PersonExternalService personExternalService;
    private UserModuleApi userModuleApi;
    private VerificationCodeStore verificationCodeStore;
    private VerificationCodeSender sender;
    private MockMvc mvc;

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(PatientGlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        patientRepository = mock(PatientRepository.class);
        personExternalService = mock(PersonExternalService.class);
        userModuleApi = mock(UserModuleApi.class);
        verificationCodeStore = mock(VerificationCodeStore.class);
        sender = mock(VerificationCodeSender.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");

        VerificationService verificationService = new VerificationService(
                verificationCodeStore, mock(VerificationAttemptProcessor.class), sender, passwordEncoder);

        PatientService patientService = new PatientService(
                patientRepository,
                personExternalService,
                userModuleApi,
                mock(UserAccountProvisioningApi.class),
                verificationService,
                mock(PatientLinkFinalizer.class));

        mvc = MockMvcBuilders.standaloneSetup(new PatientController(
                        patientService,
                        mock(PatientUpdateService.class),
                        mock(AppointmentExternalService.class),
                        mock(SecurityContextExtractor.class),
                        mock(PersonExternalService.class)))
                .setControllerAdvice(new PatientGlobalExceptionHandler())
                .build();

        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(appender);
    }

    /** Persona con paciente y cuenta, sin rol de paciente: puede pedir código. */
    private void givenEligiblePerson(String doc, String phone, String email) {
        when(personExternalService.findByIdentification(doc)).thenReturn(Optional.of(new PersonSummary(
                personId, userId, IdentificationType.CEDULA, doc, "Ana", "Ruiz", phone, email)));
        when(patientRepository.existsById(personId)).thenReturn(true);
        when(userModuleApi.getUserRoles(userId)).thenReturn(List.of());
    }

    private void givenDelivery(boolean available, boolean recipientUsable) {
        when(sender.isDeliveryAvailable()).thenReturn(available);
        when(sender.isRecipientUsable(any(), any())).thenReturn(recipientUsable);
    }

    private MvcResult request(String doc) throws Exception {
        return mvc.perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identification\":\"" + doc + "\"}"))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void assertNoRecipientOrInternalData(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(raw)
                .doesNotContain(EMAIL)
                .doesNotContain("@")
                .doesNotContain(PHONE)
                .doesNotContain(personId.toString())
                .doesNotContain(userId.toString())
                .doesNotContainIgnoringCase("invalid")
                .doesNotContainIgnoringCase("malformad")
                .doesNotContainIgnoringCase("console")
                .doesNotContainIgnoringCase("consola");
    }

    private void verifyNoCodeIssued() {
        verifyNoInteractions(verificationCodeStore);
        verify(sender, never()).sendCode(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── 503 ───────────────────────────────────────────────────────────────

    @Test
    void unavailableDeliveryAnswersTheSameForKnownAndUnknownDocuments() throws Exception {
        givenEligiblePerson(KNOWN_DOC, PHONE, EMAIL);
        givenDelivery(false, false);

        MvcResult known = request(KNOWN_DOC);
        MvcResult unknown = request(UNKNOWN_DOC);

        for (MvcResult result : List.of(known, unknown)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(503);
            assertThat(body(result).get("errorCode").asText()).isEqualTo("VERIFICATION_DELIVERY_UNAVAILABLE");
            assertThat(body(result).get("detail").asText()).isEqualTo(UNAVAILABLE_DETAIL);
            assertNoRecipientOrInternalData(result);
        }

        ObjectNode knownBody = (ObjectNode) body(known);
        ObjectNode unknownBody = (ObjectNode) body(unknown);
        knownBody.remove("timestamp");
        unknownBody.remove("timestamp");
        assertThat(knownBody).isEqualTo(unknownBody);

        // Se decide antes de buscar el documento.
        verifyNoInteractions(personExternalService, patientRepository, userModuleApi);
        verifyNoCodeIssued();
    }

    @Test
    void unavailableDeliveryIsNotRepeatedAsAWarningNorLoggedWithAStackTrace() throws Exception {
        givenDelivery(false, false);
        Level previous = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
        try {
            request(KNOWN_DOC);
            request(UNKNOWN_DOC);
        } finally {
            handlerLogger.setLevel(previous);
        }

        // El aviso operativo es el de arranque ([OTP_DELIVERY]); por solicitud solo queda DEBUG.
        assertThat(appender.list).hasSize(2).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).contains("VERIFICATION_DELIVERY_UNAVAILABLE");
        });
    }

    @Test
    void atTheDefaultLevelUnavailableDeliveryLeavesNoPerRequestLogLine() throws Exception {
        givenDelivery(false, false);

        request(KNOWN_DOC);

        assertThat(appender.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.INFO));
    }

    // ── 409 contacto ──────────────────────────────────────────────────────

    @Test
    void anUnusableContactIsAGenericConflictThatTouchesNothing() throws Exception {
        givenEligiblePerson(KNOWN_DOC, PHONE, EMAIL);
        givenDelivery(true, false);

        MvcResult result = request(KNOWN_DOC);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result).get("errorCode").asText()).isEqualTo("VERIFICATION_CONTACT_UNUSABLE");
        assertThat(body(result).get("detail").asText()).isEqualTo(CONTACT_DETAIL);
        assertNoRecipientOrInternalData(result);
        verify(sender).isRecipientUsable(PHONE, EMAIL);
        verifyNoCodeIssued();
    }

    // ── 200 ───────────────────────────────────────────────────────────────

    @Test
    void anAcceptedRequestAnswersOkWithAnEmptyBody() throws Exception {
        givenEligiblePerson(KNOWN_DOC, PHONE, "ana.ruiz@example.com");
        givenDelivery(true, true);

        MvcResult result = request(KNOWN_DOC);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        verify(sender).sendCode(eq(KNOWN_DOC), anyString(), eq(PHONE), eq("ana.ruiz@example.com"),
                anyString(), eq(5), eq(personId), any());
    }

    // ── 429 sin cambios ───────────────────────────────────────────────────

    @Test
    void theCooldownStillAnswersTooManyRequests() throws Exception {
        givenEligiblePerson(KNOWN_DOC, PHONE, "ana.ruiz@example.com");
        givenDelivery(true, true);
        when(verificationCodeStore.countIssuedSince(eq(KNOWN_DOC), any(), any())).thenReturn(1L);

        MvcResult result = request(KNOWN_DOC);

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(body(result).get("errorCode").asText()).isEqualTo("VERIFICATION_CODE_REQUEST_LIMIT");
        verify(sender, never()).sendCode(any(), any(), any(), any(), any(), anyInt(), any(), any());
    }

    // ── 409 ya vinculado: sin UUID interno ────────────────────────────────

    @Test
    void anAlreadyLinkedPatientDoesNotExposeInternalIds() throws Exception {
        givenEligiblePerson(KNOWN_DOC, PHONE, EMAIL);
        when(userModuleApi.getUserRoles(userId)).thenReturn(List.of(Role.PATIENT.name()));
        givenDelivery(true, true);

        MvcResult result = request(KNOWN_DOC);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(result).get("errorCode").asText()).isEqualTo("PATIENT_ALREADY_LINKED_USER");
        assertThat(body(result).get("detail").asText())
                .isEqualTo("El paciente ya tiene una cuenta de usuario vinculada");
        assertNoRecipientOrInternalData(result);
    }

    // ── Errores inesperados siguen visibles ───────────────────────────────

    @Test
    void anUnexpectedFailureIsStillLoggedAsAnErrorWithItsStackTrace() throws Exception {
        givenDelivery(true, true);
        when(personExternalService.findByIdentification(KNOWN_DOC)).thenThrow(new RuntimeException("boom"));

        MvcResult result = request(KNOWN_DOC);

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void anIdentityProviderFailureIsStillLoggedAsAnErrorWithItsStackTrace() throws Exception {
        givenEligiblePerson(KNOWN_DOC, PHONE, EMAIL);
        givenDelivery(true, true);
        when(userModuleApi.getUserRoles(userId)).thenThrow(new IdentityProviderException("synthetic"));

        MvcResult result = request(KNOWN_DOC);

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }
}
