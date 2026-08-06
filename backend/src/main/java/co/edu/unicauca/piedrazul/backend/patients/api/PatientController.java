package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.appointment.AppointmentExternalService;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.CreatePatientWithUserRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.ConfirmLinkUserAccountRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.CreatePatientRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.RequestLinkUserAccountCodeRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientPublicResponse;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientResponse;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientSummaryResponse;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import co.edu.unicauca.piedrazul.backend.shared.enums.IdentificationType;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;
    private final AppointmentExternalService appointmentExternalService;

    public PatientController(PatientService patientService, AppointmentExternalService appointmentExternalService) {
        this.patientService = patientService;
        this.appointmentExternalService = appointmentExternalService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PatientResponse create(@Valid @RequestBody CreatePatientRequest request) {
        PatientData patient = patientService.createPatient(
                request.getIdentificationType(),
                request.getIdentification(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhone(),
                request.getEmail(),
                null,
                request.getSex(),
                request.getBirthDate(),
                request.getGuardianPhone()
        );
        return toResponse(patient);
    }

    @PostMapping("/with-user")
    public PatientResponse createWithUser(@Valid @RequestBody CreatePatientWithUserRequest request) {
        PatientData patient = patientService.createPatientWithUser(
                request.getUsername(),
                request.getPassword(),
                request.getIdentificationType(),
                request.getIdentification(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhone(),
                request.getEmail(),
                request.getSex(),
                request.getBirthDate(),
                request.getGuardianPhone()
        );
        return toResponse(patient);
    }

    @PostMapping("/link-user-account/request-code")
    public void requestLinkUserAccountCode(@Valid @RequestBody RequestLinkUserAccountCodeRequest request) {
        patientService.requestLinkUserAccountCode(request.getIdentification());
    }

    @PostMapping("/link-user-account/confirm")
    public PatientResponse confirmLinkUserAccount(@Valid @RequestBody ConfirmLinkUserAccountRequest request) {
        PatientData patient = patientService.confirmLinkUserAccount(
                request.getIdentification(),
                request.getCode(),
                request.getPassword()
        );
        return toResponse(patient);
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('PATIENT')")
    public PatientResponse findMe(@AuthenticationPrincipal Jwt jwt) {
        UUID keycloakId = UUID.fromString(jwt.getSubject());
        PatientData patient = patientService.findByUserId(keycloakId)
                .orElseThrow(() -> new PatientNotFoundException(keycloakId));
        return toResponse(patient);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR')")
    public PatientResponse findById(@PathVariable UUID id) {
        PatientData patient = patientService.findById(id)
                .orElseThrow(() -> new PatientNotFoundException(id));
        return toResponse(patient);
    }

    @GetMapping("/document/{documentNumber}")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public PatientResponse findByDocument(@PathVariable String documentNumber) {
        PatientData patient = patientService.findByDocumentNumber(documentNumber)
                .orElseThrow(() -> new PatientNotFoundException(documentNumber));
        return toResponse(patient);
    }

    @GetMapping("/search/by-document-prefix")
    @PreAuthorize("hasAnyRole('SCHEDULER', 'PATIENT', 'DOCTOR')")
    public List<PatientSummaryResponse> searchByDocumentPrefix(@RequestParam String documentPrefix) {
        return patientService.searchByDocumentNumberPrefix(documentPrefix)
                .stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SCHEDULER', 'DOCTOR')")
    public List<PatientSummaryResponse> findAll() {
        return patientService.findAll()
                .stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    @GetMapping("/{id}/exists")
    @PreAuthorize("hasRole('SCHEDULER')")
    public boolean existsById(@PathVariable UUID id) {
        return patientService.existsById(id);
    }

    @GetMapping("/document/{documentNumber}/public")
    public PatientPublicResponse findPublicByDocument(@PathVariable String documentNumber) {
        return patientService.findPublicByDocumentNumber(documentNumber);
    }

    @GetMapping("/{appointmentId}/patient-attended")
    @PreAuthorize("hasRole('DOCTOR')")
    public PatientResponse getPatientByAppointment(@PathVariable UUID appointmentId) {
        UUID patientId = appointmentExternalService.getPattientIdByAppointmentId(appointmentId);
        PatientData patient = patientService.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));
        return toResponse(patient);
    }

    @GetMapping("/document-types")
    public List<IdentificationType> findAllDocumentTypes() {
        return patientService.getAllDocumentTypes();
    }

    private PatientResponse toResponse(PatientData patient) {
        return new PatientResponse(
                patient.personId(),
                patient.userId(),
                patient.identificationType(),
                patient.identification(),
                patient.firstName(),
                patient.lastName(),
                patient.phone(),
                patient.email(),
                patient.sex(),
                patient.birthDate(),
                patient.guardianPhone()
        );
    }

    private PatientSummaryResponse toSummaryResponse(PatientData patient) {
        return new PatientSummaryResponse(
                patient.personId(),
                patient.identification(),
                patient.firstName(),
                patient.lastName()
        );
    }
}