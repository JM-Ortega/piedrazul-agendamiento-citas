package co.edu.unicauca.piedrazul.backend.appointment.infrastructure.integration;

import co.edu.unicauca.piedrazul.backend.appointment.domain.port.output.ClinicalHistoryPort;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.ClinicalHistoryExternalService;
import co.edu.unicauca.piedrazul.backend.clinicalHistory.api.dto.input.ClinicalHistoryRequest;

import java.time.LocalDate;
import java.util.UUID;

public class ClinicalHistoryPortImpl implements ClinicalHistoryPort {
    private final ClinicalHistoryExternalService externalService;

    public ClinicalHistoryPortImpl(ClinicalHistoryExternalService externalService) {
        this.externalService = externalService;
    }

    @Override
    public void registerClinicalHistory(
            UUID appointmentId, UUID idPatient, String doctorName, String description, LocalDate attendedAt) {
        externalService.registerClinicalHistory(
            new ClinicalHistoryRequest(idPatient, appointmentId, description, doctorName, attendedAt)
        );
    }
}
