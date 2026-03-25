package co.edu.unicauca.piedrazul.backend.appointment.domain.model;

import java.util.UUID;

public record PatientSnapshot(
        UUID idPatient,
        PatientInfo patientInfo
) {}