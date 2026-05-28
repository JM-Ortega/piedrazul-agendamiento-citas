package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.patients.domain.DocumentType;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.ConfirmLinkUserAccountRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.internal.PatientData;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.CreatePatientRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.CreatePatientWithUserRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.input.RequestLinkUserAccountCodeRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientPublicResponse;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientResponse;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.output.PatientSummaryResponse;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PatientResponse create(@Valid @RequestBody CreatePatientRequest request) {
        PatientData patient = patientService.createPatient(
                request.getDocumentType(),
                request.getDocumentNumber(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhone(),
                request.getEmail(),
                request.getGender(),
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
                request.getDocumentType(),
                request.getDocumentNumber(),
                request.getFirstName(),
                request.getLastName(),
                request.getPhone(),
                request.getEmail(),
                request.getGender(),
                request.getBirthDate(),
                request.getGuardianPhone()
        );
        return toResponse(patient);
    }

    @PostMapping("/link-user-account/request-code")
    public void requestLinkUserAccountCode(@Valid @RequestBody RequestLinkUserAccountCodeRequest request) {
        patientService.requestLinkUserAccountCode(request.getDocumentNumber());
    }

    @PostMapping("/link-user-account/confirm")
    public PatientResponse confirmLinkUserAccount(@Valid @RequestBody ConfirmLinkUserAccountRequest request) {
        PatientData patient = patientService.confirmLinkUserAccount(
                request.getDocumentNumber(),
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
        UUID patientId = patientService.getPatientIdByAppointmentId(appointmentId);
        PatientData patient = patientService.findById(patientId)
                .orElseThrow(() -> new PatientNotFoundException(patientId));
        return toResponse(patient);
    }

    @GetMapping("/document-types")
    @PreAuthorize("hasAnyRole('DOCTOR', 'SCHEDULER', 'PATIENT', 'ADMIN')")
    public List<DocumentType> findAllDocumentTypes() {
        return patientService.getAllDocumentTypes();
    }

    private PatientResponse toResponse(PatientData patient) {
        return new PatientResponse(
                patient.id(),
                patient.userId(),
                patient.documentType(),
                patient.documentNumber(),
                patient.firstName(),
                patient.lastName(),
                patient.phone(),
                patient.email(),
                patient.gender(),
                patient.birthDate(),
                patient.guardianPhone()
        );
    }

    private PatientSummaryResponse toSummaryResponse(PatientData patient) {
        return new PatientSummaryResponse(
                patient.id(),
                patient.documentNumber(),
                patient.firstName(),
                patient.lastName()
        );
    }
}