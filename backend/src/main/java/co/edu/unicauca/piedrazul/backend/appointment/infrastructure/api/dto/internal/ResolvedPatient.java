package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.api.dto.internal;

import co.edu.unicauca.piedrazul.backend.appointment.domain.model.PatientInfo;

import java.util.UUID;

public record ResolvedPatient(UUID idPatient, PatientInfo patientInfo) {
}