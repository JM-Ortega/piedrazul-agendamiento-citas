package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.*;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.exception.PatientNotFoundException;
import jakarta.validation.Valid;
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

    @PostMapping("/link-user-account")
    public PatientResponse linkUserAccount(@Valid @RequestBody LinkUserAccountRequest request) {
        PatientData patient = patientService.linkUserToExistingPatient(
                request.getDocumentNumber(),
                request.getUsername()
        );

        return toResponse(patient);
    }

    @GetMapping("/{id}")
    public PatientResponse findById(@PathVariable UUID id) {
        PatientData patient = patientService.findById(id)
                .orElseThrow(() -> new PatientNotFoundException(id));

        return toResponse(patient);
    }

    @GetMapping("/document/{documentNumber}")
    public PatientResponse findByDocument(@PathVariable String documentNumber) {
        PatientData patient = patientService.findByDocumentNumber(documentNumber)
                .orElseThrow(() -> new PatientNotFoundException(documentNumber));

        return toResponse(patient);
    }

    @GetMapping
    public List<PatientSummaryResponse> findAll() {
        return patientService.findAll()
                .stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    @GetMapping("/{id}/exists")
    public boolean existsById(@PathVariable UUID id) {
        return patientService.existsById(id);
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