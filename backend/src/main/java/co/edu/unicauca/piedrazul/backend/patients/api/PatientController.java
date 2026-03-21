package co.edu.unicauca.piedrazul.backend.patients.api;

import co.edu.unicauca.piedrazul.backend.patients.api.dto.CreatePatientRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.CreatePatientWithUserRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.LinkUserAccountRequest;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientResponse;
import co.edu.unicauca.piedrazul.backend.patients.api.dto.PatientSummaryResponse;
import co.edu.unicauca.piedrazul.backend.patients.application.PatientService;
import co.edu.unicauca.piedrazul.backend.patients.domain.Patient;
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
        Patient patient = patientService.createPatient(
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
        Patient patient = patientService.createPatientWithUser(
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
        Patient patient = patientService.linkUserToExistingPatient(
                request.getDocumentNumber(),
                request.getUsername()
        );

        return toResponse(patient);
    }

    @GetMapping("/{id}")
    public PatientResponse findById(@PathVariable UUID id) {
        Patient patient = patientService.findById(id)
                .orElseThrow(() -> new PatientNotFoundException(id));

        return toResponse(patient);
    }

    @GetMapping("/document/{documentNumber}")
    public PatientResponse findByDocument(@PathVariable String documentNumber) {
        Patient patient = patientService.findByDocumentNumber(documentNumber)
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

    private PatientResponse toResponse(Patient patient) {
        return new PatientResponse(
                patient.getId(),
                patient.getUserId(),
                patient.getDocumentType(),
                patient.getDocumentNumber(),
                patient.getFirstName(),
                patient.getLastName(),
                patient.getPhone(),
                patient.getEmail(),
                patient.getGender(),
                patient.getBirthDate(),
                patient.getGuardianPhone()
        );
    }

    private PatientSummaryResponse toSummaryResponse(Patient patient) {
        return new PatientSummaryResponse(
                patient.getId(),
                patient.getDocumentNumber(),
                patient.getFirstName(),
                patient.getLastName()
        );
    }
}